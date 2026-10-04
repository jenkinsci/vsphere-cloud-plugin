package org.jenkinsci.plugins.vsphere.tools.esxi;

/**
 * How far to trust the host key that an ESXi host presents, when no fingerprint to expect is given (one that
 * is given always has to match, whatever the policy).
 */
public enum EsxiHostKeyPolicy {

    /** Trust only a host key with the configured fingerprint; with none configured, trust nothing. The safe default. */
    FINGERPRINT,

    /**
     * Trust whichever host key the host presents the first time, remember its fingerprint, and from then on
     * require that one: a changed host key (a reinstalled host, or somebody in between) is refused.
     */
    TRUST_FIRST_USE,

    /** Trust whichever host key is presented, every time. Not secure; for hosts that are only reached in a safe network. */
    ACCEPT_ANY
}
