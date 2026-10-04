package org.jenkinsci.plugins.vsphere.tools.esxi;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.trilead.ssh2.Connection;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.util.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jenkinsci.plugins.vsphere.tools.VSphereException;

/**
 * How to log in to an ESXi host over SSH: with a user name and password, or with a user name and a private
 * key (which may have a passphrase). Both kinds of credential in the Credentials plugin are supported, see
 * {@link #from(StandardUsernameCredentials)}.
 */
public abstract class EsxiSshAuth {

    private final String username;

    private EsxiSshAuth(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    /** What the login uses, to put in messages (never the secret itself). */
    abstract String describe();

    /**
     * Logs in on a connection whose server has been verified.
     *
     * @throws VSphereException if the host did not accept the credentials
     */
    abstract void authenticate(Connection connection) throws IOException, VSphereException;

    /** Logging in with a password. */
    public static EsxiSshAuth password(String username, String password) {
        return new Password(username, password);
    }

    /**
     * Logging in with a private key.
     *
     * @param privateKeys the keys in PEM format, tried in turn
     * @param passphrase what decrypts the keys, or null if they are not encrypted
     */
    public static EsxiSshAuth privateKey(String username, List<String> privateKeys, @CheckForNull String passphrase) {
        return new PrivateKey(username, privateKeys, passphrase);
    }

    /**
     * The way to log in that a credential from the Credentials plugin stands for: a "Username with password" or
     * an "SSH Username with private key".
     */
    public static EsxiSshAuth from(StandardUsernameCredentials credentials) throws VSphereException {
        if (credentials instanceof SSHUserPrivateKey) {
            final SSHUserPrivateKey key = (SSHUserPrivateKey) credentials;
            final Secret passphrase = key.getPassphrase();
            final String plain = passphrase == null ? "" : passphrase.getPlainText();
            return privateKey(key.getUsername(), key.getPrivateKeys(), plain.isEmpty() ? null : plain);
        }
        if (credentials instanceof StandardUsernamePasswordCredentials) {
            final StandardUsernamePasswordCredentials password = (StandardUsernamePasswordCredentials) credentials;
            return password(password.getUsername(), password.getPassword().getPlainText());
        }
        throw new VSphereException("The kind of credentials \"" + credentials.getId()
                + "\" cannot be used to log in over SSH; use a username with password, or an SSH username with"
                + " private key");
    }

    private static final class Password extends EsxiSshAuth {
        private final String password;

        Password(String username, String password) {
            super(username);
            this.password = password == null ? "" : password;
        }

        @Override
        String describe() {
            return "password of " + getUsername();
        }

        @Override
        void authenticate(Connection connection) throws IOException, VSphereException {
            // Hosts differ in whether a password is taken as such or only when asked for by the
            // keyboard-interactive method, so what the server offers decides.
            if (connection.isAuthMethodAvailable(getUsername(), "password")
                    && connection.authenticateWithPassword(getUsername(), password)) {
                return;
            }
            if (connection.isAuthMethodAvailable(getUsername(), "keyboard-interactive")
                    && connection.authenticateWithKeyboardInteractive(
                            getUsername(), (name, instruction, numPrompts, prompt, echo) -> {
                                final String[] answers = new String[numPrompts];
                                Arrays.fill(answers, password);
                                return answers;
                            })) {
                return;
            }
            throw new VSphereException("The host did not accept the " + describe());
        }
    }

    private static final class PrivateKey extends EsxiSshAuth {
        private final List<String> privateKeys;
        private final String passphrase;

        PrivateKey(String username, List<String> privateKeys, @CheckForNull String passphrase) {
            super(username);
            this.privateKeys = privateKeys == null ? new ArrayList<>() : new ArrayList<>(privateKeys);
            this.passphrase = passphrase;
        }

        @Override
        String describe() {
            return "private key of " + getUsername();
        }

        @Override
        void authenticate(Connection connection) throws IOException, VSphereException {
            if (privateKeys.isEmpty()) {
                throw new VSphereException("There is no private key to log in as " + getUsername() + " with");
            }
            String lastProblem = null;
            for (String key : privateKeys) {
                try {
                    if (connection.authenticateWithPublicKey(getUsername(), key.toCharArray(), passphrase)) {
                        return;
                    }
                } catch (IOException e) {
                    // an unreadable key, a wrong passphrase, or a key that the host refused
                    lastProblem = e.getMessage();
                }
            }
            throw new VSphereException("The host did not accept the " + describe()
                    + (lastProblem == null ? "" : " (" + lastProblem + ")"));
        }
    }
}
