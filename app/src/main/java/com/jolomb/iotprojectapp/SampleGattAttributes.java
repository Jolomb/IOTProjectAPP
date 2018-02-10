/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jolomb.iotprojectapp;

import java.util.HashMap;

/**
 * This class includes a small subset of standard GATT attributes for demonstration purposes.
 */
public class SampleGattAttributes {
    private static HashMap<String, String> attributes = new HashMap();
    public static String CRYPTO_SIGNER_CHALLANGE_INPUT = "0000fff1-0000-1000-8000-00805f9b34fb";
    public static String CRYPTO_SIGNER_SIGNED_RESPONSE = "0000fff2-0000-1000-8000-00805f9b34fb";
    public static String CRYPTO_SIGNER_RESPONSE_STATE = "0000fff3-0000-1000-8000-00805f9b34fb";
    public static String CRYPTO_SIGNER_SERVICE  = "0000fff0-0000-1000-8000-00805f9b34fb";

    static {
        attributes.put(CRYPTO_SIGNER_SERVICE, "Crypto Signing Service");
        attributes.put(CRYPTO_SIGNER_CHALLANGE_INPUT, "Crypto challange input buffer");
        attributes.put(CRYPTO_SIGNER_SIGNED_RESPONSE, "Crypto signed response buffer");
        attributes.put(CRYPTO_SIGNER_RESPONSE_STATE, "State of the signed response");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }
}
