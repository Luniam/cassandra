/*
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
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.apache.cassandra.cql3.CQL3Type;
import org.apache.cassandra.cql3.Constants;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.serializers.TypeSerializer;
import org.apache.cassandra.serializers.UUIDSerializer;
import org.apache.cassandra.utils.TimeUUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.cassandra.utils.TimeUUID.Generator.nextTimeUUIDAsBytes;

// Fully compatible with UUID, and indeed is interpreted as UUID for UDF
public abstract class AbstractTimeUUIDType<T> extends TemporalType<T>
{
    AbstractTimeUUIDType()
    {
        super(ComparisonType.CUSTOM);
    } // singleton

    public boolean isEmptyValueMeaningless()
    {
        return true;
    }

    public <VL, VR> int compareCustom(VL left, ValueAccessor<VL> accessorL, VR right, ValueAccessor<VR> accessorR)
    {
        // Compare for length
        boolean p1 = accessorL.size(left) == 16, p2 = accessorR.size(right) == 16;
        if (!(p1 & p2))
        {
            // should we assert exactly 16 bytes (or 0)? seems prudent
            assert p1 || accessorL.isEmpty(left);
            assert p2 || accessorR.isEmpty(right);
            return p1 ? 1 : p2 ? -1 : 0;
        }

        long msb1 = accessorL.getLong(left, 0);
        long msb2 = accessorR.getLong(right, 0);
        msb1 = reorderTimestampBytes(msb1);
        msb2 = reorderTimestampBytes(msb2);

        assert (msb1 & topbyte(0xf0L)) == topbyte(0x10L);
        assert (msb2 & topbyte(0xf0L)) == topbyte(0x10L);

        int c = Long.compare(msb1, msb2);
        if (c != 0)
            return c;

        // this has to be a signed per-byte comparison for compatibility
        // so we transform the bytes so that a simple long comparison is equivalent
        long lsb1 = signedBytesToNativeLong(accessorL.getLong(left, 8));
        long lsb2 = signedBytesToNativeLong(accessorR.getLong(right, 8));
        return Long.compare(lsb1, lsb2);
    }

    // takes as input 8 signed bytes in native machine order
    // returns the first byte unchanged, and the following 7 bytes converted to an unsigned representation
    // which is the same as a 2's complement long in native format
    public static long signedBytesToNativeLong(long signedBytes)
    {
        return signedBytes ^ 0x0080808080808080L;
    }

    private static long topbyte(long topbyte)
    {
        return topbyte << 56;
    }

    protected static long reorderTimestampBytes(long input)
    {
        return    (input <<  48)
                  | ((input <<  16) & 0xFFFF00000000L)
                  |  (input >>> 32);
    }

    public ByteBuffer fromString(String source) throws MarshalException
    {
        ByteBuffer parsed = UUIDType.parse(source);
        if (parsed == null)
            throw new MarshalException(String.format("Unknown timeuuid representation: %s", source));
        if (parsed.remaining() == 16 && UUIDType.version(parsed) != 1)
            throw new MarshalException("TimeUUID supports only version 1 UUIDs");
        return parsed;
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
        try
        {
            return new Constants.Value(fromString((String) parsed));
        }
        catch (ClassCastException exc)
        {
            throw new MarshalException(
                    String.format("Expected a string representation of a timeuuid, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));
        }
    }

    public CQL3Type asCQL3Type()
    {
        return CQL3Type.Native.TIMEUUID;
    }

    @Override
    public ByteBuffer decomposeUntyped(Object value)
    {
        if (value instanceof UUID)
            return UUIDSerializer.instance.serialize((UUID) value);
        if (value instanceof TimeUUID)
            return TimeUUID.Serializer.instance.serialize((TimeUUID) value);
        return super.decomposeUntyped(value);
    }

    @Override
    public int valueLengthIfFixed()
    {
        return 16;
    }

    @Override
    public long toTimeInMillis(ByteBuffer value)
    {
        return TimeUUID.deserialize(value).unix(MILLISECONDS);
    }

    @Override
    public ByteBuffer addDuration(ByteBuffer temporal, ByteBuffer duration)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuffer substractDuration(ByteBuffer temporal, ByteBuffer duration)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public ByteBuffer now()
    {
        return ByteBuffer.wrap(nextTimeUUIDAsBytes());
    }

    @Override
    public boolean equals(Object obj)
    {
        return obj instanceof AbstractTimeUUIDType<?>;
    }
}
