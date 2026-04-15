/*******************************************************************************
 * Copyright (c) 2025 RISE and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 ******************************************************************************/
package org.eclipse.californium.oscore;

/**
 * OSCORE header values (kid, piv, kid context) cached from the phantom
 * registration request associated with a multicast observation. A client uses
 * these when reconstructing the AAD to decrypt multicast notifications that
 * share the observation's token.
 */
public final class PhantomRequestValues {

	private final byte[] kid;
	private final byte[] piv;
	private final byte[] kidContext;

	public PhantomRequestValues(byte[] kid, byte[] piv, byte[] kidContext) {
		this.kid = kid;
		this.piv = piv;
		this.kidContext = kidContext;
	}

	public byte[] getKid() {
		return kid;
	}

	public byte[] getPiv() {
		return piv;
	}

	public byte[] getKidContext() {
		return kidContext;
	}
}
