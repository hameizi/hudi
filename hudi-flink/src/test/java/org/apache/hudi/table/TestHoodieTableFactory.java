/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table;

import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.hive.MultiPartKeysValueExtractor;
import org.apache.hudi.hive.SlashEncodedDayPartitionValueExtractor;
import org.apache.hudi.keygen.ComplexAvroKeyGenerator;
import org.apache.hudi.keygen.NonpartitionedAvroKeyGenerator;
import org.apache.hudi.util.StreamerUtil;
import org.apache.hudi.utils.SchemaBuilder;
import org.apache.hudi.utils.TestConfigurations;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test cases for {@link HoodieTableFactory}.
 */
public class TestHoodieTableFactory {
  private static final String AVRO_SCHEMA_FILE_PATH = Objects.requireNonNull(Thread.currentThread()
      .getContextClassLoader().getResource("test_read_schema.avsc")).toString();
  private static final String INFERRED_SCHEMA = "{\"type\":\"record\","
      + "\"name\":\"record\","
      + "\"fields\":["
      + "{\"name\":\"uuid\",\"type\":[\"null\",\"string\"],\"default\":null},"
      + "{\"name\":\"name\",\"type\":[\"null\",\"string\"],\"default\":null},"
      + "{\"name\":\"age\",\"type\":[\"null\",\"int\"],\"default\":null},"
      + "{\"name\":\"ts\",\"type\":[\"null\",{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}],\"default\":null},"
      + "{\"name\":\"partition\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

  private Configuration conf;

  @TempDir
  File tempFile;

  @BeforeEach
  void beforeEach() throws IOException {
    this.conf = new Configuration();
    this.conf.setString(FlinkOptions.PATH, tempFile.getAbsolutePath());
    this.conf.setString(FlinkOptions.TABLE_NAME, "t1");
    StreamerUtil.initTableIfNotExists(this.conf);
  }

  @Test
  void testRequiredOptionsForSource() {
    // miss pk and pre combine key will throw exception
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .build();
    final MockContext sourceContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    assertThrows(ValidationException.class, () -> new HoodieTableFactory().createDynamicTableSource(sourceContext1));
    assertThrows(ValidationException.class, () -> new HoodieTableFactory().createDynamicTableSink(sourceContext1));

    // given the pk and miss the pre combine key will throw exception
    ResolvedSchema schema2 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    final MockContext sourceContext2 = MockContext.getInstance(this.conf, schema2, "f2");
    assertThrows(ValidationException.class, () -> new HoodieTableFactory().createDynamicTableSource(sourceContext2));
    assertThrows(ValidationException.class, () -> new HoodieTableFactory().createDynamicTableSink(sourceContext2));

    // given pk and pre combine key will be ok
    ResolvedSchema schema3 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    final MockContext sourceContext3 = MockContext.getInstance(this.conf, schema3, "f2");

    assertDoesNotThrow(() -> new HoodieTableFactory().createDynamicTableSource(sourceContext3));
    assertDoesNotThrow(() -> new HoodieTableFactory().createDynamicTableSink(sourceContext3));
  }

  @Test
  void testInferAvroSchemaForSource() {
    // infer the schema if not specified
    final HoodieTableSource tableSource1 =
        (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(MockContext.getInstance(this.conf));
    final Configuration conf1 = tableSource1.getConf();
    assertThat(conf1.get(FlinkOptions.SOURCE_AVRO_SCHEMA), is(INFERRED_SCHEMA));

    // set up the explicit schema using the file path
    this.conf.setString(FlinkOptions.SOURCE_AVRO_SCHEMA_PATH, AVRO_SCHEMA_FILE_PATH);
    HoodieTableSource tableSource2 =
        (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(MockContext.getInstance(this.conf));
    Configuration conf2 = tableSource2.getConf();
    assertNull(conf2.get(FlinkOptions.SOURCE_AVRO_SCHEMA), "expect schema string as null");
  }

  @Test
  void testSetupHoodieKeyOptionsForSource() {
    this.conf.setString(FlinkOptions.RECORD_KEY_FIELD, "dummyField");
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, "dummyKeyGenClass");
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    final MockContext sourceContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSource tableSource1 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext1);
    final Configuration conf1 = tableSource1.getConf();
    assertThat(conf1.get(FlinkOptions.RECORD_KEY_FIELD), is("f0"));
    assertThat(conf1.get(FlinkOptions.KEYGEN_CLASS_NAME), is("dummyKeyGenClass"));

    // definition with complex primary keys and partition paths
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, FlinkOptions.KEYGEN_CLASS_NAME.defaultValue());
    ResolvedSchema schema2 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20).notNull())
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0", "f1")
        .build();
    final MockContext sourceContext2 = MockContext.getInstance(this.conf, schema2, "f2");
    final HoodieTableSource tableSource2 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext2);
    final Configuration conf2 = tableSource2.getConf();
    assertThat(conf2.get(FlinkOptions.RECORD_KEY_FIELD), is("f0,f1"));
    assertThat(conf2.get(FlinkOptions.KEYGEN_CLASS_NAME), is(ComplexAvroKeyGenerator.class.getName()));

    // definition with complex primary keys and empty partition paths
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, FlinkOptions.KEYGEN_CLASS_NAME.defaultValue());
    final MockContext sourceContext3 = MockContext.getInstance(this.conf, schema2, "");
    final HoodieTableSource tableSource3 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext3);
    final Configuration conf3 = tableSource3.getConf();
    assertThat(conf3.get(FlinkOptions.RECORD_KEY_FIELD), is("f0,f1"));
    assertThat(conf3.get(FlinkOptions.KEYGEN_CLASS_NAME), is(NonpartitionedAvroKeyGenerator.class.getName()));
  }

  @Test
  void testSetupHiveOptionsForSource() {
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();

    final MockContext sourceContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSource tableSource1 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext1);
    final Configuration conf1 = tableSource1.getConf();
    assertThat(conf1.getString(FlinkOptions.HIVE_SYNC_PARTITION_EXTRACTOR_CLASS_NAME), is(MultiPartKeysValueExtractor.class.getName()));

    // set up hive style partitioning is true.
    this.conf.setBoolean(FlinkOptions.HIVE_STYLE_PARTITIONING, true);

    final MockContext sourceContext2 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSource tableSource2 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext2);
    final Configuration conf2 = tableSource2.getConf();
    assertThat(conf2.getString(FlinkOptions.HIVE_SYNC_PARTITION_EXTRACTOR_CLASS_NAME), is(SlashEncodedDayPartitionValueExtractor.class.getName()));
  }

  @Test
  void testSetupCleaningOptionsForSource() {
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    // set up new retains commits that is less than min archive commits
    this.conf.setString(FlinkOptions.CLEAN_RETAIN_COMMITS.key(), "11");

    final MockContext sourceContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSource tableSource1 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext1);
    final Configuration conf1 = tableSource1.getConf();
    assertThat(conf1.getInteger(FlinkOptions.ARCHIVE_MIN_COMMITS), is(20));
    assertThat(conf1.getInteger(FlinkOptions.ARCHIVE_MAX_COMMITS), is(30));

    // set up new retains commits that is greater than min archive commits
    this.conf.setString(FlinkOptions.CLEAN_RETAIN_COMMITS.key(), "25");

    final MockContext sourceContext2 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSource tableSource2 = (HoodieTableSource) new HoodieTableFactory().createDynamicTableSource(sourceContext2);
    final Configuration conf2 = tableSource2.getConf();
    assertThat(conf2.getInteger(FlinkOptions.ARCHIVE_MIN_COMMITS), is(35));
    assertThat(conf2.getInteger(FlinkOptions.ARCHIVE_MAX_COMMITS), is(45));
  }

  @Test
  void testInferAvroSchemaForSink() {
    // infer the schema if not specified
    final HoodieTableSink tableSink1 =
        (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(MockContext.getInstance(this.conf));
    final Configuration conf1 = tableSink1.getConf();
    assertThat(conf1.get(FlinkOptions.SOURCE_AVRO_SCHEMA), is(INFERRED_SCHEMA));

    // set up the explicit schema using the file path
    this.conf.setString(FlinkOptions.SOURCE_AVRO_SCHEMA_PATH, AVRO_SCHEMA_FILE_PATH);
    HoodieTableSink tableSink2 =
        (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(MockContext.getInstance(this.conf));
    Configuration conf2 = tableSink2.getConf();
    assertNull(conf2.get(FlinkOptions.SOURCE_AVRO_SCHEMA), "expect schema string as null");
  }

  @Test
  void testSetupHoodieKeyOptionsForSink() {
    this.conf.setString(FlinkOptions.RECORD_KEY_FIELD, "dummyField");
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, "dummyKeyGenClass");
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    final MockContext sinkContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSink tableSink1 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext1);
    final Configuration conf1 = tableSink1.getConf();
    assertThat(conf1.get(FlinkOptions.RECORD_KEY_FIELD), is("f0"));
    assertThat(conf1.get(FlinkOptions.KEYGEN_CLASS_NAME), is("dummyKeyGenClass"));

    // definition with complex primary keys and partition paths
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, FlinkOptions.KEYGEN_CLASS_NAME.defaultValue());
    ResolvedSchema schema2 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20).notNull())
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0", "f1")
        .build();
    final MockContext sinkContext2 = MockContext.getInstance(this.conf, schema2, "f2");
    final HoodieTableSink tableSink2 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext2);
    final Configuration conf2 = tableSink2.getConf();
    assertThat(conf2.get(FlinkOptions.RECORD_KEY_FIELD), is("f0,f1"));
    assertThat(conf2.get(FlinkOptions.KEYGEN_CLASS_NAME), is(ComplexAvroKeyGenerator.class.getName()));

    // definition with complex primary keys and empty partition paths
    this.conf.setString(FlinkOptions.KEYGEN_CLASS_NAME, FlinkOptions.KEYGEN_CLASS_NAME.defaultValue());
    final MockContext sinkContext3 = MockContext.getInstance(this.conf, schema2, "");
    final HoodieTableSink tableSink3 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext3);
    final Configuration conf3 = tableSink3.getConf();
    assertThat(conf3.get(FlinkOptions.RECORD_KEY_FIELD), is("f0,f1"));
    assertThat(conf3.get(FlinkOptions.KEYGEN_CLASS_NAME), is(NonpartitionedAvroKeyGenerator.class.getName()));
  }

  @Test
  void testSetupHiveOptionsForSink() {
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();

    final MockContext sinkContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSink tableSink1 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext1);
    final Configuration conf1 = tableSink1.getConf();
    assertThat(conf1.getString(FlinkOptions.HIVE_SYNC_PARTITION_EXTRACTOR_CLASS_NAME), is(MultiPartKeysValueExtractor.class.getName()));

    // set up hive style partitioning is true.
    this.conf.setBoolean(FlinkOptions.HIVE_STYLE_PARTITIONING, true);

    final MockContext sinkContext2 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSink tableSink2 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext2);
    final Configuration conf2 = tableSink2.getConf();
    assertThat(conf2.getString(FlinkOptions.HIVE_SYNC_PARTITION_EXTRACTOR_CLASS_NAME), is(SlashEncodedDayPartitionValueExtractor.class.getName()));
  }

  @Test
  void testSetupCleaningOptionsForSink() {
    // definition with simple primary key and partition path
    ResolvedSchema schema1 = SchemaBuilder.instance()
        .field("f0", DataTypes.INT().notNull())
        .field("f1", DataTypes.VARCHAR(20))
        .field("f2", DataTypes.TIMESTAMP(3))
        .field("ts", DataTypes.TIMESTAMP(3))
        .primaryKey("f0")
        .build();
    // set up new retains commits that is less than min archive commits
    this.conf.setString(FlinkOptions.CLEAN_RETAIN_COMMITS.key(), "11");

    final MockContext sinkContext1 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSink tableSink1 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext1);
    final Configuration conf1 = tableSink1.getConf();
    assertThat(conf1.getInteger(FlinkOptions.ARCHIVE_MIN_COMMITS), is(20));
    assertThat(conf1.getInteger(FlinkOptions.ARCHIVE_MAX_COMMITS), is(30));

    // set up new retains commits that is greater than min archive commits
    this.conf.setString(FlinkOptions.CLEAN_RETAIN_COMMITS.key(), "25");

    final MockContext sinkContext2 = MockContext.getInstance(this.conf, schema1, "f2");
    final HoodieTableSink tableSink2 = (HoodieTableSink) new HoodieTableFactory().createDynamicTableSink(sinkContext2);
    final Configuration conf2 = tableSink2.getConf();
    assertThat(conf2.getInteger(FlinkOptions.ARCHIVE_MIN_COMMITS), is(35));
    assertThat(conf2.getInteger(FlinkOptions.ARCHIVE_MAX_COMMITS), is(45));
  }

  // -------------------------------------------------------------------------
  //  Inner Class
  // -------------------------------------------------------------------------

  /**
   * Mock dynamic table factory context.
   */
  private static class MockContext implements DynamicTableFactory.Context {
    private final Configuration conf;
    private final ResolvedSchema schema;
    private final List<String> partitions;

    private MockContext(Configuration conf, ResolvedSchema schema, List<String> partitions) {
      this.conf = conf;
      this.schema = schema;
      this.partitions = partitions;
    }

    static MockContext getInstance(Configuration conf) {
      return getInstance(conf, TestConfigurations.TABLE_SCHEMA, Collections.singletonList("partition"));
    }

    static MockContext getInstance(Configuration conf, ResolvedSchema schema, String partition) {
      return getInstance(conf, schema, Collections.singletonList(partition));
    }

    static MockContext getInstance(Configuration conf, ResolvedSchema schema, List<String> partitions) {
      return new MockContext(conf, schema, partitions);
    }

    @Override
    public ObjectIdentifier getObjectIdentifier() {
      return ObjectIdentifier.of("hudi", "default", "t1");
    }

    @Override
    public ResolvedCatalogTable getCatalogTable() {
      CatalogTable catalogTable = CatalogTable.of(Schema.newBuilder().fromResolvedSchema(schema).build(),
          "mock source table", partitions, conf.toMap());
      return new ResolvedCatalogTable(catalogTable, schema);
    }

    @Override
    public ReadableConfig getConfiguration() {
      return conf;
    }

    @Override
    public ClassLoader getClassLoader() {
      return null;
    }

    @Override
    public boolean isTemporary() {
      return false;
    }
  }
}
