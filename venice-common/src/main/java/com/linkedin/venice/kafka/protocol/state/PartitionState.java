/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.linkedin.venice.kafka.protocol.state;

@SuppressWarnings("all")
/** This record holds the state necessary for a consumer to checkpoint its progress when consuming a Venice partition. When provided the state in this record, a consumer should thus be able to resume consuming midway through a stream. */
public class PartitionState extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = org.apache.avro.Schema.parse("{\"type\":\"record\",\"name\":\"PartitionState\",\"namespace\":\"com.linkedin.venice.kafka.protocol.state\",\"fields\":[{\"name\":\"offset\",\"type\":\"long\",\"doc\":\"The last Kafka offset consumed successfully in this partition.\"},{\"name\":\"endOfPush\",\"type\":\"boolean\",\"doc\":\"Whether the EndOfPush control message was consumed in this partition.\"},{\"name\":\"sorted\",\"type\":\"boolean\",\"doc\":\"Whether the messages inside current topic partition between 'StartOfPush' control message and 'EndOfPush' control message is lexicographically sorted by key bytes\",\"default\":false},{\"name\":\"lastUpdate\",\"type\":\"long\",\"doc\":\"The last time this PartitionState was updated. Can be compared against the various messageTimestamp in ProducerPartitionState in order to infer lag time between producers and the consumer maintaining this PartitionState.\"},{\"name\":\"producerStates\",\"type\":{\"type\":\"map\",\"values\":{\"type\":\"record\",\"name\":\"ProducerPartitionState\",\"fields\":[{\"name\":\"segmentNumber\",\"type\":\"int\",\"doc\":\"The current segment number corresponds to the last (highest) segment number for which we have seen a StartOfSegment control message.\"},{\"name\":\"segmentStatus\",\"type\":\"int\",\"doc\":\"The status of the current segment: 0 => NOT_STARTED, 1 => IN_PROGRESS, 2 => END_OF_INTERMEDIATE_SEGMENT, 3 => END_OF_FINAL_SEGMENT.\"},{\"name\":\"messageSequenceNumber\",\"type\":\"int\",\"doc\":\"The current message sequence number, within the current segment, which we have seen for this partition/producer pair.\"},{\"name\":\"messageTimestamp\",\"type\":\"long\",\"doc\":\"The timestamp included in the last message we have seen for this partition/producer pair.\"},{\"name\":\"checksumType\",\"type\":\"int\",\"doc\":\"The current mapping is the following: 0 => None, 1 => MD5, 2 => Adler32, 3 => CRC32.\"},{\"name\":\"checksumState\",\"type\":\"bytes\",\"doc\":\"The value of the checksum computed since the last StartOfSegment ControlMessage.\"},{\"name\":\"aggregates\",\"type\":{\"type\":\"map\",\"values\":\"long\"},\"doc\":\"The aggregates that have been computed so far since the last StartOfSegment ControlMessage.\"},{\"name\":\"debugInfo\",\"type\":{\"type\":\"map\",\"values\":\"string\"},\"doc\":\"The debug info received as part of the last StartOfSegment ControlMessage.\"}]}},\"doc\":\"A map of producer GUID -> producer state.\"}]}");
  /** The last Kafka offset consumed successfully in this partition. */
  public long offset;
  /** Whether the EndOfPush control message was consumed in this partition. */
  public boolean endOfPush;
  /** Whether the messages inside current topic partition between 'StartOfPush' control message and 'EndOfPush' control message is lexicographically sorted by key bytes */
  public boolean sorted;
  /** The last time this PartitionState was updated. Can be compared against the various messageTimestamp in ProducerPartitionState in order to infer lag time between producers and the consumer maintaining this PartitionState. */
  public long lastUpdate;
  /** A map of producer GUID -> producer state. */
  public java.util.Map<java.lang.CharSequence,com.linkedin.venice.kafka.protocol.state.ProducerPartitionState> producerStates;
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return offset;
    case 1: return endOfPush;
    case 2: return sorted;
    case 3: return lastUpdate;
    case 4: return producerStates;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: offset = (java.lang.Long)value$; break;
    case 1: endOfPush = (java.lang.Boolean)value$; break;
    case 2: sorted = (java.lang.Boolean)value$; break;
    case 3: lastUpdate = (java.lang.Long)value$; break;
    case 4: producerStates = (java.util.Map<java.lang.CharSequence,com.linkedin.venice.kafka.protocol.state.ProducerPartitionState>)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
}
