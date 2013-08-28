/*   Copyright 2013, MANDIANT, Eric Lordahl
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.jenkinsci.plugins.vsphere.tools;

public class VSphereException extends Exception {

	private static final long serialVersionUID = -6133908887091288919L;


	public VSphereException() { 
		super(); 
	}

	public VSphereException(String message) {
		super(message); 
	}

	public VSphereException(String message, Throwable cause) {
		super(message, cause); 
	}

	public VSphereException(Throwable cause) {
		super(cause);
	}
}
