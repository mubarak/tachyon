/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tachyon.client;

import java.io.IOException;

/**
 * Different read types for a TachyonFile.
 */
public enum ReadType {
  /**
   * Read the file and but do not cache it explicitly.
   */
  NO_CACHE(1),
  /**
   * Read the file and cache it.
   */
  CACHE(2);

  /**
   * Parse the read type
   * 
   * @param op
   *          the String format of the read type
   * @return the read type
   * @throws IOException
   */
  public static ReadType getOpType(String op) throws IOException {
    if (op.equals("NO_CACHE")) {
      return NO_CACHE;
    } else if (op.equals("CACHE")) {
      return CACHE;
    }

    throw new IOException("Unknown ReadType : " + op);
  }

  private final int VALUE;

  private ReadType(int value) {
    VALUE = value;
  }

  /**
   * Return the value of the read type
   * 
   * @return the read type value
   */
  public int getValue() {
    return VALUE;
  }

  /**
   * @return true if the read type is CACHE, false otherwise
   */
  public boolean isCache() {
    return VALUE == CACHE.VALUE;
  }
}
