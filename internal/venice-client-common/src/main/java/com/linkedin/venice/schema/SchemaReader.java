package com.linkedin.venice.schema;

import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import java.io.Closeable;
import org.apache.avro.Schema;


/**
 * This interface is considered as an internal interface to Venice codebase. Venice's users should consider using
 * {@link com.linkedin.venice.client.schema.StoreSchemaFetcher} to fetch Venice store schemas.
 */
public interface SchemaReader extends Closeable {
  Schema getKeySchema();

  Schema getValueSchema(int id);

  /**
   * Return the schema ID of any schema that has the same parsing canonical form as the schema provided.
   * @param schema The schema for which the schema ID is needed
   * @return The ID of the schema that has the same parsing canonical form as the schema provided
   */
  int getValueSchemaId(Schema schema);

  Schema getLatestValueSchema();

  /**
   * Get the latest value schema id. This may be different from the value schema with the largest id if the superset
   * schema is not the value schema with the largest id
   */
  Integer getLatestValueSchemaId();

  Schema getUpdateSchema(int valueSchemaId);

  DerivedSchemaEntry getLatestUpdateSchema();
}
