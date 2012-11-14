/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.hadoop.hbase.thrift.generated;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import com.facebook.thrift.*;

import com.facebook.thrift.protocol.*;
import com.facebook.thrift.transport.*;

/**
 * TCell - Used to transport a cell value (byte[]) and the timestamp it was
 * stored with together as a result for get and getRow methods. This promotes
 * the timestamp of a cell to a first-class value, making it easy to take
 * note of temporal data. Cell is used all the way from HStore up to HTable.
 */
public class TCell implements TBase, java.io.Serializable {
  public byte[] value;
  public long timestamp;

  public final Isset __isset = new Isset();
  public static final class Isset implements java.io.Serializable {
    public boolean value = false;
    public boolean timestamp = false;
  }

  public TCell() {
  }

  public TCell(
    byte[] value,
    long timestamp)
  {
    this();
    this.value = value;
    this.__isset.value = true;
    this.timestamp = timestamp;
    this.__isset.timestamp = true;
  }

  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof TCell)
      return this.equals((TCell)that);
    return false;
  }

  public boolean equals(TCell that) {
    if (that == null)
      return false;

    boolean this_present_value = true && (this.value != null);
    boolean that_present_value = true && (that.value != null);
    if (this_present_value || that_present_value) {
      if (!(this_present_value && that_present_value))
        return false;
      if (!java.util.Arrays.equals(this.value, that.value))
        return false;
    }

    boolean this_present_timestamp = true;
    boolean that_present_timestamp = true;
    if (this_present_timestamp || that_present_timestamp) {
      if (!(this_present_timestamp && that_present_timestamp))
        return false;
      if (this.timestamp != that.timestamp)
        return false;
    }

    return true;
  }

  public int hashCode() {
    return 0;
  }

  public void read(TProtocol iprot) throws TException {
    TField field;
    iprot.readStructBegin();
    while (true)
    {
      field = iprot.readFieldBegin();
      if (field.type == TType.STOP) { 
        break;
      }
      switch (field.id)
      {
        case 1:
          if (field.type == TType.STRING) {
            this.value = iprot.readBinary();
            this.__isset.value = true;
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2:
          if (field.type == TType.I64) {
            this.timestamp = iprot.readI64();
            this.__isset.timestamp = true;
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          TProtocolUtil.skip(iprot, field.type);
          break;
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();
  }

  public void write(TProtocol oprot) throws TException {
    TStruct struct = new TStruct("TCell");
    oprot.writeStructBegin(struct);
    TField field = new TField();
    if (this.value != null) {
      field.name = "value";
      field.type = TType.STRING;
      field.id = 1;
      oprot.writeFieldBegin(field);
      oprot.writeBinary(this.value);
      oprot.writeFieldEnd();
    }
    field.name = "timestamp";
    field.type = TType.I64;
    field.id = 2;
    oprot.writeFieldBegin(field);
    oprot.writeI64(this.timestamp);
    oprot.writeFieldEnd();
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  public String toString() {
    StringBuilder sb = new StringBuilder("TCell(");
    sb.append("value:");
    sb.append(this.value);
    sb.append(",timestamp:");
    sb.append(this.timestamp);
    sb.append(")");
    return sb.toString();
  }

}
