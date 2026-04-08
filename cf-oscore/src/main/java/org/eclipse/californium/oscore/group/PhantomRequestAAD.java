package org.eclipse.californium.oscore.group;

/**
 * Holds the OSCORE header values extracted from a phantom request.
 * Clients use these to reconstruct the AAD when decrypting multicast notifications.
 */
public class PhantomRequestAAD {

	private byte[] kid;
	private byte[] piv;
	private byte[] kidContext;

	public PhantomRequestAAD(byte[] kid, byte[] piv, byte[] kidContext) {
		this.kid = kid;
		this.piv = piv;
		this.kidContext = kidContext;
	}

	public byte[] getKid() {
		return kid;
	}

	public void setKid(byte[] kid) {
		this.kid = kid;
	}

	public byte[] getPiv() {
		return piv;
	}

	public void setPiv(byte[] piv) {
		this.piv = piv;
	}

	public byte[] getKidContext() {
		return kidContext;
	}

	public void setKidContext(byte[] kidContext) {
		this.kidContext = kidContext;
	}

	public boolean isValid() {
		return kid != null && piv != null;
	}
}
