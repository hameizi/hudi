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

import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.configuration.FlinkOptions;
import org.apache.hudi.util.StreamerUtil;
import org.apache.hudi.utils.TestConfigurations;
import org.apache.hudi.utils.TestData;
import org.apache.hudi.utils.TestSQL;
import org.apache.hudi.utils.TestUtils;
import org.apache.hudi.utils.factory.CollectSinkTableFactory;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.config.ExecutionConfigOptions;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.utils.TestConfigurations.sql;
import static org.apache.hudi.utils.TestData.assertRowsEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IT cases for Hoodie table source and sink.
 */
public class HoodieDataSourceITCase extends AbstractTestBase {
  private TableEnvironment streamTableEnv;
  private TableEnvironment batchTableEnv;

  @BeforeEach
  void beforeEach() {
    EnvironmentSettings settings = EnvironmentSettings.newInstance().build();
    streamTableEnv = TableEnvironmentImpl.create(settings);
    streamTableEnv.getConfig().getConfiguration()
        .setInteger(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM, 1);
    streamTableEnv.getConfig().getConfiguration()
        .setString("execution.checkpointing.interval", "2s");

    settings = EnvironmentSettings.newInstance().inBatchMode().build();
    batchTableEnv = TableEnvironmentImpl.create(settings);
    batchTableEnv.getConfig().getConfiguration()
        .setInteger(ExecutionConfigOptions.TABLE_EXEC_RESOURCE_DEFAULT_PARALLELISM, 1);
  }

  @TempDir
  File tempFile;

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class)
  void testStreamWriteAndReadFromSpecifiedCommit(HoodieTableType tableType) throws Exception {
    // create filesystem table named source
    String createSource = TestConfigurations.getFileSourceDDL("source");
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 select * from source";
    execInsertSql(streamTableEnv, insertInto);

    String firstCommit = TestUtils.getFirstCommit(tempFile.getAbsolutePath());
    streamTableEnv.executeSql("drop table t1");
    hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .option(FlinkOptions.READ_STREAMING_START_COMMIT, firstCommit)
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    List<Row> rows = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows, TestData.DATA_SET_SOURCE_INSERT);

    // insert another batch of data
    execInsertSql(streamTableEnv, insertInto);
    List<Row> rows2 = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows2, TestData.DATA_SET_SOURCE_INSERT);

    streamTableEnv.getConfig().getConfiguration()
        .setBoolean("table.dynamic-table-options.enabled", true);
    // specify the start commit as earliest
    List<Row> rows3 = execSelectSql(streamTableEnv,
        "select * from t1/*+options('read.streaming.start-commit'='earliest')*/", 10);
    assertRowsEquals(rows3, TestData.DATA_SET_SOURCE_INSERT);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class)
  void testStreamWriteAndRead(HoodieTableType tableType) throws Exception {
    // create filesystem table named source
    String createSource = TestConfigurations.getFileSourceDDL("source");
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 select * from source";
    execInsertSql(streamTableEnv, insertInto);

    // reading from latest commit instance.
    List<Row> rows = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows, TestData.DATA_SET_SOURCE_INSERT_LATEST_COMMIT);

    // insert another batch of data
    execInsertSql(streamTableEnv, insertInto);
    List<Row> rows2 = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows2, TestData.DATA_SET_SOURCE_INSERT_LATEST_COMMIT);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class)
  void testStreamReadAppendData(HoodieTableType tableType) throws Exception {
    // create filesystem table named source
    String createSource = TestConfigurations.getFileSourceDDL("source");
    String createSource2 = TestConfigurations.getFileSourceDDL("source2", "test_source_2.data");
    streamTableEnv.executeSql(createSource);
    streamTableEnv.executeSql(createSource2);

    String createHoodieTable = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .end();
    streamTableEnv.executeSql(createHoodieTable);
    String insertInto = "insert into t1 select * from source";
    // execute 2 times
    execInsertSql(streamTableEnv, insertInto);
    // remember the commit
    String specifiedCommit = TestUtils.getFirstCommit(tempFile.getAbsolutePath());
    // another update batch
    String insertInto2 = "insert into t1 select * from source2";
    execInsertSql(streamTableEnv, insertInto2);
    // now we consume starting from the oldest commit
    String createHoodieTable2 = sql("t2")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .option(FlinkOptions.READ_STREAMING_START_COMMIT, specifiedCommit)
        .end();
    streamTableEnv.executeSql(createHoodieTable2);
    List<Row> rows = execSelectSql(streamTableEnv, "select * from t2", 10);
    // all the data with same keys are appended within one data bucket and one log file,
    // so when consume, the same keys are merged
    assertRowsEquals(rows, TestData.DATA_SET_SOURCE_MERGED);
  }

  @Test
  void testStreamWriteBatchRead() {
    // create filesystem table named source
    String createSource = TestConfigurations.getFileSourceDDL("source");
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 select * from source";
    execInsertSql(streamTableEnv, insertInto);

    List<Row> rows = CollectionUtil.iterableToList(
        () -> streamTableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(rows, TestData.DATA_SET_SOURCE_INSERT);
  }

  @Test
  void testStreamWriteBatchReadOptimized() {
    // create filesystem table named source
    String createSource = TestConfigurations.getFileSourceDDL("source");
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ)
        // read optimized is supported for both MOR and COR table,
        // test MOR streaming write with compaction then reads as
        // query type 'read_optimized'.
        .option(FlinkOptions.QUERY_TYPE, FlinkOptions.QUERY_TYPE_READ_OPTIMIZED)
        .option(FlinkOptions.COMPACTION_DELTA_COMMITS, "1")
        .option(FlinkOptions.COMPACTION_TASKS, "1")
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 select * from source";
    execInsertSql(streamTableEnv, insertInto);

    List<Row> rows = CollectionUtil.iterableToList(
        () -> streamTableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(rows, TestData.DATA_SET_SOURCE_INSERT);
  }

  @Test
  void testStreamWriteWithCleaning() {
    // create filesystem table named source

    // the source generates 4 commits but the cleaning task
    // would always try to keep the remaining commits number as 1
    String createSource = TestConfigurations.getFileSourceDDL(
        "source", "test_source_3.data", 4);
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.CLEAN_RETAIN_COMMITS, "1")
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 select * from source";
    execInsertSql(streamTableEnv, insertInto);

    Configuration defaultConf = TestConfigurations.getDefaultConf(tempFile.getAbsolutePath());
    Map<String, String> options1 = new HashMap<>(defaultConf.toMap());
    options1.put(FlinkOptions.TABLE_NAME.key(), "t1");
    Configuration conf = Configuration.fromMap(options1);
    HoodieTimeline timeline = StreamerUtil.createWriteClient(conf, null)
        .getHoodieTable().getActiveTimeline();
    assertTrue(timeline.filterCompletedInstants()
            .getInstants().anyMatch(instant -> instant.getAction().equals("clean")),
        "some commits should be cleaned");
  }

  @Test
  void testStreamReadWithDeletes() throws Exception {
    // create filesystem table named source

    Configuration conf = TestConfigurations.getDefaultConf(tempFile.getAbsolutePath());
    conf.setString(FlinkOptions.TABLE_NAME, "t1");
    conf.setString(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ);
    conf.setBoolean(FlinkOptions.CHANGELOG_ENABLED, true);

    // write one commit
    TestData.writeData(TestData.DATA_SET_INSERT, conf);
    // write another commit with deletes
    TestData.writeData(TestData.DATA_SET_UPDATE_DELETE, conf);

    String latestCommit = StreamerUtil.createWriteClient(conf, null)
        .getLastCompletedInstant(HoodieTableType.MERGE_ON_READ);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ)
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.READ_STREAMING_CHECK_INTERVAL, "2")
        .option(FlinkOptions.READ_STREAMING_START_COMMIT, latestCommit)
        .option(FlinkOptions.CHANGELOG_ENABLED, "true")
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);

    final String sinkDDL = "create table sink(\n"
        + "  name varchar(20),\n"
        + "  age_sum int\n"
        + ") with (\n"
        + "  'connector' = '" + CollectSinkTableFactory.FACTORY_ID + "'"
        + ")";
    List<Row> result = execSelectSql(streamTableEnv,
        "select name, sum(age) from t1 group by name", sinkDDL, 10);
    final String expected = "[+I(+I[Danny, 24]), +I(+I[Stephen, 34])]";
    assertRowsEquals(result, expected, true);
  }

  @ParameterizedTest
  @MethodSource("tableTypeAndPartitioningParams")
  void testStreamReadFilterByPartition(HoodieTableType tableType, boolean hiveStylePartitioning) throws Exception {
    Configuration conf = TestConfigurations.getDefaultConf(tempFile.getAbsolutePath());
    conf.setString(FlinkOptions.TABLE_NAME, "t1");
    conf.setString(FlinkOptions.TABLE_TYPE, tableType.name());
    conf.setBoolean(FlinkOptions.HIVE_STYLE_PARTITIONING, hiveStylePartitioning);

    // write one commit
    TestData.writeData(TestData.DATA_SET_INSERT, conf);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_TYPE, tableType.name())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.READ_STREAMING_CHECK_INTERVAL, "2")
        .option(FlinkOptions.HIVE_STYLE_PARTITIONING, hiveStylePartitioning)
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);

    List<Row> result = execSelectSql(streamTableEnv,
        "select * from t1 where `partition`='par1'", 10);
    final String expected = "["
        + "+I(+I[id1, Danny, 23, 1970-01-01T00:00:00.001, par1]), "
        + "+I(+I[id2, Stephen, 33, 1970-01-01T00:00:00.002, par1])]";
    assertRowsEquals(result, expected, true);
  }

  @Test
  void testStreamReadMorTableWithCompactionPlan() throws Exception {
    String createSource = TestConfigurations.getFileSourceDDL("source");
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ)
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.READ_STREAMING_START_COMMIT, FlinkOptions.START_COMMIT_EARLIEST)
        .option(FlinkOptions.READ_STREAMING_CHECK_INTERVAL, "2")
        // close the async compaction
        .option(FlinkOptions.COMPACTION_ASYNC_ENABLED, false)
        // generate compaction plan for each commit
        .option(FlinkOptions.COMPACTION_DELTA_COMMITS, "1")
        .withPartition(false)
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);

    streamTableEnv.executeSql("insert into t1 select * from source");

    List<Row> result = execSelectSql(streamTableEnv, "select * from t1", 10);
    final String expected = "["
        + "+I[id1, Danny, 23, 1970-01-01T00:00:01, par1], "
        + "+I[id2, Stephen, 33, 1970-01-01T00:00:02, par1], "
        + "+I[id3, Julian, 53, 1970-01-01T00:00:03, par2], "
        + "+I[id4, Fabian, 31, 1970-01-01T00:00:04, par2], "
        + "+I[id5, Sophia, 18, 1970-01-01T00:00:05, par3], "
        + "+I[id6, Emma, 20, 1970-01-01T00:00:06, par3], "
        + "+I[id7, Bob, 44, 1970-01-01T00:00:07, par4], "
        + "+I[id8, Han, 56, 1970-01-01T00:00:08, par4]]";
    assertRowsEquals(result, expected);
  }

  @ParameterizedTest
  @MethodSource("executionModeAndPartitioningParams")
  void testWriteAndRead(ExecMode execMode, boolean hiveStylePartitioning) {
    TableEnvironment tableEnv = execMode == ExecMode.BATCH ? batchTableEnv : streamTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.HIVE_STYLE_PARTITIONING, hiveStylePartitioning)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    execInsertSql(tableEnv, TestSQL.INSERT_T1);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT);
    // apply filters
    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1 where uuid > 'id5'").execute().collect());
    assertRowsEquals(result2, "["
        + "+I[id6, Emma, 20, 1970-01-01T00:00:06, par3], "
        + "+I[id7, Bob, 44, 1970-01-01T00:00:07, par4], "
        + "+I[id8, Han, 56, 1970-01-01T00:00:08, par4]]");
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class)
  void testBatchModeUpsertWithoutPartition(HoodieTableType tableType) {
    TableEnvironment tableEnv = batchTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_NAME, tableType.name())
        .withPartition(false)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    execInsertSql(tableEnv, TestSQL.INSERT_T1);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT);

    // batchMode update
    execInsertSql(tableEnv, TestSQL.UPDATE_INSERT_T1);
    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result2, TestData.DATA_SET_SOURCE_MERGED);
  }

  @ParameterizedTest
  @MethodSource("tableTypeAndPartitioningParams")
  void testBatchModeUpsert(HoodieTableType tableType, boolean hiveStylePartitioning) {
    TableEnvironment tableEnv = batchTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_NAME, tableType.name())
        .option(FlinkOptions.HIVE_STYLE_PARTITIONING, hiveStylePartitioning)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    execInsertSql(tableEnv, TestSQL.INSERT_T1);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT);

    // batchMode update
    execInsertSql(tableEnv, TestSQL.UPDATE_INSERT_T1);

    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result2, TestData.DATA_SET_SOURCE_MERGED);
  }

  @ParameterizedTest
  @EnumSource(value = ExecMode.class)
  void testWriteAndReadParMiddle(ExecMode execMode) throws Exception {
    boolean streaming = execMode == ExecMode.STREAM;
    String hoodieTableDDL = "create table t1(\n"
        + "  uuid varchar(20),\n"
        + "  name varchar(10),\n"
        + "  age int,\n"
        + "  `partition` varchar(20),\n" // test streaming read with partition field in the middle
        + "  ts timestamp(3),\n"
        + "  PRIMARY KEY(uuid) NOT ENFORCED\n"
        + ")\n"
        + "PARTITIONED BY (`partition`)\n"
        + "with (\n"
        + "  'connector' = 'hudi',\n"
        + "  'path' = '" + tempFile.getAbsolutePath() + "',\n"
        + "  'read.streaming.enabled' = '" + streaming + "'\n"
        + ")";
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into t1 values\n"
        + "('id1','Danny',23,'par1',TIMESTAMP '1970-01-01 00:00:01'),\n"
        + "('id2','Stephen',33,'par1',TIMESTAMP '1970-01-01 00:00:02'),\n"
        + "('id3','Julian',53,'par2',TIMESTAMP '1970-01-01 00:00:03'),\n"
        + "('id4','Fabian',31,'par2',TIMESTAMP '1970-01-01 00:00:04'),\n"
        + "('id5','Sophia',18,'par3',TIMESTAMP '1970-01-01 00:00:05'),\n"
        + "('id6','Emma',20,'par3',TIMESTAMP '1970-01-01 00:00:06'),\n"
        + "('id7','Bob',44,'par4',TIMESTAMP '1970-01-01 00:00:07'),\n"
        + "('id8','Han',56,'par4',TIMESTAMP '1970-01-01 00:00:08')";
    execInsertSql(streamTableEnv, insertInto);

    final String expected = "["
        + "+I[id1, Danny, 23, par1, 1970-01-01T00:00:01], "
        + "+I[id2, Stephen, 33, par1, 1970-01-01T00:00:02], "
        + "+I[id3, Julian, 53, par2, 1970-01-01T00:00:03], "
        + "+I[id4, Fabian, 31, par2, 1970-01-01T00:00:04], "
        + "+I[id5, Sophia, 18, par3, 1970-01-01T00:00:05], "
        + "+I[id6, Emma, 20, par3, 1970-01-01T00:00:06], "
        + "+I[id7, Bob, 44, par4, 1970-01-01T00:00:07], "
        + "+I[id8, Han, 56, par4, 1970-01-01T00:00:08]]";

    List<Row> result = execSelectSql(streamTableEnv, "select * from t1", execMode);

    assertRowsEquals(result, expected);

    // insert another batch of data
    execInsertSql(streamTableEnv, insertInto);
    List<Row> result2 = execSelectSql(streamTableEnv, "select * from t1", execMode);
    assertRowsEquals(result2, expected);
  }

  @ParameterizedTest
  @EnumSource(value = ExecMode.class)
  void testInsertOverwrite(ExecMode execMode) {
    TableEnvironment tableEnv = execMode == ExecMode.BATCH ? batchTableEnv : streamTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    execInsertSql(tableEnv, TestSQL.INSERT_T1);

    // overwrite partition 'par1' and increase in age by 1
    final String insertInto2 = "insert overwrite t1 partition(`partition`='par1') values\n"
        + "('id1','Danny',24,TIMESTAMP '1970-01-01 00:00:01'),\n"
        + "('id2','Stephen',34,TIMESTAMP '1970-01-01 00:00:02')\n";

    execInsertSql(tableEnv, insertInto2);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT_OVERWRITE);

    // overwrite the whole table
    final String insertInto3 = "insert overwrite t1 values\n"
        + "('id1','Danny',24,TIMESTAMP '1970-01-01 00:00:01', 'par1'),\n"
        + "('id2','Stephen',34,TIMESTAMP '1970-01-01 00:00:02', 'par2')\n";

    execInsertSql(tableEnv, insertInto3);

    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    final String expected = "["
        + "+I[id1, Danny, 24, 1970-01-01T00:00:01, par1], "
        + "+I[id2, Stephen, 34, 1970-01-01T00:00:02, par2]]";
    assertRowsEquals(result2, expected);
  }

  @ParameterizedTest
  @EnumSource(value = ExecMode.class)
  void testUpsertWithMiniBatches(ExecMode execMode) {
    TableEnvironment tableEnv = execMode == ExecMode.BATCH ? batchTableEnv : streamTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.WRITE_BATCH_SIZE, "0.001")
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    final String insertInto1 = "insert into t1 values\n"
        + "('id1','Danny',23,TIMESTAMP '1970-01-01 00:00:01','par1')";

    execInsertSql(tableEnv, insertInto1);

    final String insertInto2 = "insert into t1 values\n"
        + "('id1','Stephen',33,TIMESTAMP '1970-01-01 00:00:02','par1'),\n"
        + "('id1','Julian',53,TIMESTAMP '1970-01-01 00:00:03','par1'),\n"
        + "('id1','Fabian',31,TIMESTAMP '1970-01-01 00:00:04','par1'),\n"
        + "('id1','Sophia',18,TIMESTAMP '1970-01-01 00:00:05','par1')";

    execInsertSql(tableEnv, insertInto2);

    List<Row> result = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result, "[+I[id1, Sophia, 18, 1970-01-01T00:00:05, par1]]");
  }

  @ParameterizedTest
  @EnumSource(value = ExecMode.class)
  void testWriteNonPartitionedTable(ExecMode execMode) {
    TableEnvironment tableEnv = execMode == ExecMode.BATCH ? batchTableEnv : streamTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .withPartition(false)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    final String insertInto1 = "insert into t1 values\n"
        + "('id1','Danny',23,TIMESTAMP '1970-01-01 00:00:01','par1')";

    execInsertSql(tableEnv, insertInto1);

    final String insertInto2 = "insert into t1 values\n"
        + "('id1','Stephen',33,TIMESTAMP '1970-01-01 00:00:02','par2'),\n"
        + "('id1','Julian',53,TIMESTAMP '1970-01-01 00:00:03','par3'),\n"
        + "('id1','Fabian',31,TIMESTAMP '1970-01-01 00:00:04','par4'),\n"
        + "('id1','Sophia',18,TIMESTAMP '1970-01-01 00:00:05','par5')";

    execInsertSql(tableEnv, insertInto2);

    List<Row> result = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result, "[+I[id1, Sophia, 18, 1970-01-01T00:00:05, par5]]");
  }

  @Test
  void testWriteGlobalIndex() {
    // the source generates 4 commits
    String createSource = TestConfigurations.getFileSourceDDL(
        "source", "test_source_4.data", 4);
    streamTableEnv.executeSql(createSource);

    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.INDEX_GLOBAL_ENABLED, "true")
        .option(FlinkOptions.INSERT_DROP_DUPS, "true")
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);

    final String insertInto2 = "insert into t1 select * from source";

    execInsertSql(streamTableEnv, insertInto2);

    List<Row> result = CollectionUtil.iterableToList(
        () -> streamTableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result, "[+I[id1, Phoebe, 52, 1970-01-01T00:00:08, par4]]");
  }

  @Test
  void testWriteLocalIndex() {
    // the source generates 4 commits
    String createSource = TestConfigurations.getFileSourceDDL(
        "source", "test_source_4.data", 4);
    streamTableEnv.executeSql(createSource);
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.INDEX_GLOBAL_ENABLED, "false")
        .option(FlinkOptions.INSERT_DROP_DUPS, "true")
        .end();
    streamTableEnv.executeSql(hoodieTableDDL);

    final String insertInto2 = "insert into t1 select * from source";

    execInsertSql(streamTableEnv, insertInto2);

    List<Row> result = CollectionUtil.iterableToList(
        () -> streamTableEnv.sqlQuery("select * from t1").execute().collect());
    final String expected = "["
        + "+I[id1, Stephen, 34, 1970-01-01T00:00:02, par1], "
        + "+I[id1, Fabian, 32, 1970-01-01T00:00:04, par2], "
        + "+I[id1, Jane, 19, 1970-01-01T00:00:06, par3], "
        + "+I[id1, Phoebe, 52, 1970-01-01T00:00:08, par4]]";
    assertRowsEquals(result, expected, 3);
  }

  @Test
  void testStreamReadEmptyTablePath() throws Exception {
    // case1: table metadata path does not exists
    // create a flink source table
    String createHoodieTable = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.READ_AS_STREAMING, "true")
        .option(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ)
        .end();
    streamTableEnv.executeSql(createHoodieTable);

    // no exception expects to be thrown
    List<Row> rows1 = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows1, "[]");

    // case2: empty table without data files
    Configuration conf = TestConfigurations.getDefaultConf(tempFile.getAbsolutePath());
    StreamerUtil.initTableIfNotExists(conf);

    List<Row> rows2 = execSelectSql(streamTableEnv, "select * from t1", 10);
    assertRowsEquals(rows2, "[]");
  }

  @Test
  void testBatchReadEmptyTablePath() throws Exception {
    // case1: table metadata path does not exists
    // create a flink source table
    String createHoodieTable = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.TABLE_TYPE, FlinkOptions.TABLE_TYPE_MERGE_ON_READ)
        .end();
    batchTableEnv.executeSql(createHoodieTable);

    // no exception expects to be thrown
    assertThrows(Exception.class,
        () -> execSelectSql(batchTableEnv, "select * from t1", 10),
        "Exception should throw when querying non-exists table in batch mode");

    // case2: empty table without data files
    Configuration conf = TestConfigurations.getDefaultConf(tempFile.getAbsolutePath());
    StreamerUtil.initTableIfNotExists(conf);

    List<Row> rows2 = CollectionUtil.iteratorToList(batchTableEnv.executeSql("select * from t1").collect());
    assertRowsEquals(rows2, "[]");
  }

  @ParameterizedTest
  @EnumSource(value = ExecMode.class)
  void testWriteAndReadDebeziumJson(ExecMode execMode) throws Exception {
    String sourcePath = Objects.requireNonNull(Thread.currentThread()
        .getContextClassLoader().getResource("debezium_json.data")).toString();
    String sourceDDL = ""
        + "CREATE TABLE debezium_source(\n"
        + "  id INT NOT NULL,\n"
        + "  ts BIGINT,\n"
        + "  name STRING,\n"
        + "  description STRING,\n"
        + "  weight DOUBLE\n"
        + ") WITH (\n"
        + "  'connector' = 'filesystem',\n"
        + "  'path' = '" + sourcePath + "',\n"
        + "  'format' = 'debezium-json'\n"
        + ")";
    streamTableEnv.executeSql(sourceDDL);
    String hoodieTableDDL = ""
        + "CREATE TABLE hoodie_sink(\n"
        + "  id INT NOT NULL,\n"
        + "  ts BIGINT,\n"
        + "  name STRING,"
        + "  weight DOUBLE,"
        + "  PRIMARY KEY (id) NOT ENFORCED"
        + ") with (\n"
        + "  'connector' = 'hudi',\n"
        + "  'path' = '" + tempFile.getAbsolutePath() + "',\n"
        + "  'read.streaming.enabled' = '" + (execMode == ExecMode.STREAM) + "',\n"
        + "  'write.insert.drop.duplicates' = 'true'"
        + ")";
    streamTableEnv.executeSql(hoodieTableDDL);
    String insertInto = "insert into hoodie_sink select id, ts, name, weight from debezium_source";
    execInsertSql(streamTableEnv, insertInto);

    final String expected = "["
        + "+I[101, 1000, scooter, 3.140000104904175], "
        + "+I[102, 2000, car battery, 8.100000381469727], "
        + "+I[103, 3000, 12-pack drill bits, 0.800000011920929], "
        + "+I[104, 4000, hammer, 0.75], "
        + "+I[105, 5000, hammer, 0.875], "
        + "+I[106, 10000, hammer, 1.0], "
        + "+I[107, 11000, rocks, 5.099999904632568], "
        + "+I[108, 8000, jacket, 0.10000000149011612], "
        + "+I[109, 9000, spare tire, 22.200000762939453], "
        + "+I[110, 14000, jacket, 0.5]]";

    List<Row> result = execSelectSql(streamTableEnv, "select * from hoodie_sink", execMode);

    assertRowsEquals(result, expected);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testBulkInsert(boolean hiveStylePartitioning) {
    TableEnvironment tableEnv = batchTableEnv;
    // csv source
    String csvSourceDDL = TestConfigurations.getCsvSourceDDL("csv_source", "test_source_5.data");
    tableEnv.executeSql(csvSourceDDL);

    String hoodieTableDDL = sql("hoodie_sink")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.OPERATION, "bulk_insert")
        .option(FlinkOptions.WRITE_BULK_INSERT_SHUFFLE_BY_PARTITION, "true")
        .option(FlinkOptions.HIVE_STYLE_PARTITIONING, hiveStylePartitioning)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    String insertInto = "insert into hoodie_sink select * from csv_source";
    execInsertSql(tableEnv, insertInto);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from hoodie_sink").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT);
    // apply filters
    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from hoodie_sink where uuid > 'id5'").execute().collect());
    assertRowsEquals(result2, "["
        + "+I[id6, Emma, 20, 1970-01-01T00:00:06, par3], "
        + "+I[id7, Bob, 44, 1970-01-01T00:00:07, par4], "
        + "+I[id8, Han, 56, 1970-01-01T00:00:08, par4]]");
  }

  @Test
  void testBulkInsertNonPartitionedTable() {
    TableEnvironment tableEnv = batchTableEnv;
    String hoodieTableDDL = sql("t1")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.OPERATION, "bulk_insert")
        .withPartition(false)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    final String insertInto1 = "insert into t1 values\n"
        + "('id1','Danny',23,TIMESTAMP '1970-01-01 00:00:01','par1')";

    execInsertSql(tableEnv, insertInto1);

    final String insertInto2 = "insert into t1 values\n"
        + "('id1','Stephen',33,TIMESTAMP '1970-01-01 00:00:02','par2'),\n"
        + "('id1','Julian',53,TIMESTAMP '1970-01-01 00:00:03','par3'),\n"
        + "('id1','Fabian',31,TIMESTAMP '1970-01-01 00:00:04','par4'),\n"
        + "('id1','Sophia',18,TIMESTAMP '1970-01-01 00:00:05','par5')";

    execInsertSql(tableEnv, insertInto2);

    List<Row> result = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from t1").execute().collect());
    assertRowsEquals(result, "["
        + "+I[id1, Danny, 23, 1970-01-01T00:00:01, par1], "
        + "+I[id1, Stephen, 33, 1970-01-01T00:00:02, par2], "
        + "+I[id1, Julian, 53, 1970-01-01T00:00:03, par3], "
        + "+I[id1, Fabian, 31, 1970-01-01T00:00:04, par4], "
        + "+I[id1, Sophia, 18, 1970-01-01T00:00:05, par5]]", 3);
  }

  @Test
  void testAppendWrite() {
    TableEnvironment tableEnv = batchTableEnv;
    // csv source
    String csvSourceDDL = TestConfigurations.getCsvSourceDDL("csv_source", "test_source_5.data");
    tableEnv.executeSql(csvSourceDDL);

    String hoodieTableDDL = sql("hoodie_sink")
        .option(FlinkOptions.PATH, tempFile.getAbsolutePath())
        .option(FlinkOptions.OPERATION, "insert")
        .option(FlinkOptions.INSERT_DEDUP, false)
        .end();
    tableEnv.executeSql(hoodieTableDDL);

    String insertInto = "insert into hoodie_sink select * from csv_source";
    execInsertSql(tableEnv, insertInto);

    List<Row> result1 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from hoodie_sink").execute().collect());
    assertRowsEquals(result1, TestData.DATA_SET_SOURCE_INSERT);
    // apply filters
    List<Row> result2 = CollectionUtil.iterableToList(
        () -> tableEnv.sqlQuery("select * from hoodie_sink where uuid > 'id5'").execute().collect());
    assertRowsEquals(result2, "["
        + "+I[id6, Emma, 20, 1970-01-01T00:00:06, par3], "
        + "+I[id7, Bob, 44, 1970-01-01T00:00:07, par4], "
        + "+I[id8, Han, 56, 1970-01-01T00:00:08, par4]]");
  }

  // -------------------------------------------------------------------------
  //  Utilities
  // -------------------------------------------------------------------------
  private enum ExecMode {
    BATCH, STREAM
  }

  /**
   * Return test params => (execution mode, hive style partitioning).
   */
  private static Stream<Arguments> executionModeAndPartitioningParams() {
    Object[][] data =
        new Object[][] {
            {ExecMode.BATCH, false},
            {ExecMode.BATCH, true},
            {ExecMode.STREAM, false},
            {ExecMode.STREAM, true}};
    return Stream.of(data).map(Arguments::of);
  }

  /**
   * Return test params => (HoodieTableType, hive style partitioning).
   */
  private static Stream<Arguments> tableTypeAndPartitioningParams() {
    Object[][] data =
        new Object[][] {
            {HoodieTableType.COPY_ON_WRITE, false},
            {HoodieTableType.COPY_ON_WRITE, true},
            {HoodieTableType.MERGE_ON_READ, false},
            {HoodieTableType.MERGE_ON_READ, true}};
    return Stream.of(data).map(Arguments::of);
  }

  private void execInsertSql(TableEnvironment tEnv, String insert) {
    TableResult tableResult = tEnv.executeSql(insert);
    // wait to finish
    try {
      tableResult.getJobClient().get().getJobExecutionResult().get();
    } catch (InterruptedException | ExecutionException ex) {
      throw new RuntimeException(ex);
    }
  }

  private List<Row> execSelectSql(TableEnvironment tEnv, String select, ExecMode execMode)
      throws TableNotExistException, InterruptedException {
    final String[] splits = select.split(" ");
    final String tableName = splits[splits.length - 1];
    switch (execMode) {
      case STREAM:
        return execSelectSql(tEnv, select, 10, tableName);
      case BATCH:
        return CollectionUtil.iterableToList(
            () -> tEnv.sqlQuery("select * from " + tableName).execute().collect());
      default:
        throw new AssertionError();
    }
  }

  private List<Row> execSelectSql(TableEnvironment tEnv, String select, long timeout)
      throws InterruptedException, TableNotExistException {
    return execSelectSql(tEnv, select, timeout, null);
  }

  private List<Row> execSelectSql(TableEnvironment tEnv, String select, long timeout, String sourceTable)
      throws InterruptedException, TableNotExistException {
    final String sinkDDL;
    if (sourceTable != null) {
      // use the source table schema as the sink schema if the source table was specified, .
      ObjectPath objectPath = new ObjectPath(tEnv.getCurrentDatabase(), sourceTable);
      TableSchema schema = tEnv.getCatalog(tEnv.getCurrentCatalog()).get().getTable(objectPath).getSchema();
      sinkDDL = TestConfigurations.getCollectSinkDDL("sink", schema);
    } else {
      sinkDDL = TestConfigurations.getCollectSinkDDL("sink");
    }
    return execSelectSql(tEnv, select, sinkDDL, timeout);
  }

  private List<Row> execSelectSql(TableEnvironment tEnv, String select, String sinkDDL, long timeout)
      throws InterruptedException {
    tEnv.executeSql("DROP TABLE IF EXISTS sink");
    tEnv.executeSql(sinkDDL);
    TableResult tableResult = tEnv.executeSql("insert into sink " + select);
    // wait for the timeout then cancels the job
    TimeUnit.SECONDS.sleep(timeout);
    tableResult.getJobClient().ifPresent(JobClient::cancel);
    tEnv.executeSql("DROP TABLE IF EXISTS sink");
    return CollectSinkTableFactory.RESULT.values().stream()
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }
}
