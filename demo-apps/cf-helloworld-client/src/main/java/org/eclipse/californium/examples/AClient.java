/*******************************************************************************
 * Copyright (c) 2020 Bosch.IO GmbH and others.
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
 * Contributors:
 *    Bosch.IO GmbH - initial implementation
 ******************************************************************************/
package org.eclipse.californium.examples;

import java.io.IOException;
import org.eclipse.californium.elements.exception.ConnectorException;


/**
 * Main starter class for jar execution.
 */
public class AClient {

	public static void main(String[] args) throws IOException, ConnectorException, InterruptedException {
		
		String uri = "coap://127.0.0.1:5683/pasta/";
		String uri_get = "get";
		String[] resource = new String[]{uri+uri_get};
		
		String content;
		String uri_obs = uri+"obs";
		
		String uriMult = uri+"mult";
		int rounds = 4;

		
		switch (args[0]) {
			case "observe":
				System.out.println("OBSERVE");
				ObserveClient.main(uri_obs, 20);
				break;
			case "get":
				System.out.println("GET");				
				GETClient.main(resource);
				break;
			case "post":
				System.out.println("POST");
				content = Integer.toString((int)(Math.random() * 50 + 1));
				POSTClient.main(uri, content);
				break;
			case "put":	
				System.out.println("executing random put: " + rounds + "times");
				
				for(int i = 0; i < rounds; i++) {
					content = Integer.toString((int)(Math.random() * 50 + 1));
					PUTClient.main(uri_obs, content);
				}	
				break;
			case "put-mult":		
				content = Integer.toString((int)(Math.random() * 50 + 1));
				PUTClient.main(uriMult, content);
					
				break;
				
			case "mult":
				System.out.println("MULTI");
				MulticastObserveClient.main(uriMult,1000);
				break;				
				
			case "mix":
				System.out.println("-----------[GET]-------------");
				GETClient.main(resource);
				
				System.out.println("-----------[POST]-------------");
				content = Integer.toString((int)(Math.random() * 50 + 1));
				POSTClient.main(uri, content);

				System.out.println("-----------[GET]-------------");
				GETClient.main(resource);
				break;				
		}
		System.exit(-1);
	}	
}
