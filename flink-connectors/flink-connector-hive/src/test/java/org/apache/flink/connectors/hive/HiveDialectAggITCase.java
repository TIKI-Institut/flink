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

package org.apache.flink.connectors.hive;

import org.apache.flink.table.api.SqlDialect;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.catalog.hive.HiveTestUtils;
import org.apache.flink.table.module.CoreModule;
import org.apache.flink.table.module.hive.HiveModule;
import org.apache.flink.types.Row;
import org.apache.flink.util.CollectionUtil;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.apache.flink.connectors.hive.HiveOptions.TABLE_EXEC_HIVE_NATIVE_AGG_FUNCTION_ENABLED;
import static org.apache.flink.core.testutils.FlinkAssertions.anyCauseMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test for native hive agg function compatibility. */
public class HiveDialectAggITCase {

    @ClassRule public static TemporaryFolder tempFolder = new TemporaryFolder();

    private static HiveCatalog hiveCatalog;
    private static TableEnvironment tableEnv;

    @BeforeClass
    public static void setup() throws Exception {
        hiveCatalog = HiveTestUtils.createHiveCatalog();
        // required by query like "src.`[k].*` from src"
        hiveCatalog.getHiveConf().setVar(HiveConf.ConfVars.HIVE_QUOTEDID_SUPPORT, "none");
        hiveCatalog.open();
        tableEnv = getTableEnvWithHiveCatalog();

        // create tables
        tableEnv.executeSql("create table foo (x int, y int)");

        HiveTestUtils.createTextTableInserter(hiveCatalog, "default", "foo")
                .addRow(new Object[] {1, 1})
                .addRow(new Object[] {2, 2})
                .addRow(new Object[] {3, 3})
                .addRow(new Object[] {4, 4})
                .addRow(new Object[] {5, 5})
                .commit();
    }

    @Before
    public void before() {
        // enable native hive agg function
        tableEnv.getConfig().set(TABLE_EXEC_HIVE_NATIVE_AGG_FUNCTION_ENABLED, true);
    }

    @Test
    public void testSimpleSumAggFunction() throws Exception {
        tableEnv.executeSql(
                "create table test_sum(x string, y string, z int, d decimal(10,5), e float, f double, ts timestamp)");
        tableEnv.executeSql(
                        "insert into test_sum values (NULL, '2', 1, 1.11, 1.2, 1.3, '2021-08-04 16:26:33.4'), "
                                + "(NULL, 'b', 2, 2.22, 2.3, 2.4, '2021-08-07 16:26:33.4'), "
                                + "(NULL, '4', 3, 3.33, 3.5, 3.6, '2021-08-08 16:26:33.4'), "
                                + "(NULL, NULL, 4, 4.45, 4.7, 4.8, '2021-08-09 16:26:33.4')")
                .await();

        // test sum with all elements are null
        List<Row> result =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(x) from test_sum").collect());
        assertThat(result.toString()).isEqualTo("[+I[null]]");

        // test sum string type with partial element can't convert to double, result type is double
        List<Row> result2 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(y) from test_sum").collect());
        assertThat(result2.toString()).isEqualTo("[+I[6.0]]");

        // test decimal type
        List<Row> result3 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(d) from test_sum").collect());
        assertThat(result3.toString()).isEqualTo("[+I[11.11000]]");

        // test sum int, result type is bigint
        List<Row> result4 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(z) from test_sum").collect());
        assertThat(result4.toString()).isEqualTo("[+I[10]]");

        // test float type
        List<Row> result5 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(e) from test_sum").collect());
        float actualFloatValue = ((Double) result5.get(0).getField(0)).floatValue();
        assertThat(actualFloatValue).isEqualTo(11.7f);

        // test double type
        List<Row> result6 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(f) from test_sum").collect());
        actualFloatValue = ((Double) result6.get(0).getField(0)).floatValue();
        assertThat(actualFloatValue).isEqualTo(12.1f);

        // test sum string&int type simultaneously
        List<Row> result7 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select sum(y), sum(z) from test_sum").collect());
        assertThat(result7.toString()).isEqualTo("[+I[6.0, 10]]");

        // test unsupported timestamp type
        assertThatThrownBy(
                        () ->
                                CollectionUtil.iteratorToList(
                                        tableEnv.executeSql("select sum(ts) from test_sum")
                                                .collect()))
                .rootCause()
                .satisfiesAnyOf(
                        anyCauseMatches(
                                "Native hive sum aggregate function does not support type: TIMESTAMP(9). "
                                        + "Please set option 'table.exec.hive.native-agg-function.enabled' to false."));

        tableEnv.executeSql("drop table test_sum");
    }

    @Test
    public void testSumAggWithGroupKey() throws Exception {
        tableEnv.executeSql(
                "create table test_sum_group(name string, num bigint, price decimal(10,5))");
        tableEnv.executeSql(
                        "insert into test_sum_group values ('tom', 2, 7.2), ('tony', 2, 23.7), ('tom', 10, 3.33), ('tony', 4, 4.45), ('nadal', 4, 10.455)")
                .await();

        List<Row> result =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql(
                                        "select name, sum(num), sum(price),  sum(num * price) from test_sum_group group by name")
                                .collect());
        assertThat(result.toString())
                .isEqualTo(
                        "[+I[tom, 12, 10.53000, 47.70000], +I[tony, 6, 28.15000, 65.20000], +I[nadal, 4, 10.45500, 41.82000]]");

        tableEnv.executeSql("drop table test_sum_group");
    }

    @Test
    public void testMinAggFunction() throws Exception {
        tableEnv.executeSql(
                "create table test_min(a int, b boolean, x string, y string, z int, d decimal(10,5), e float, f double, ts timestamp, dt date, bar binary)");
        tableEnv.executeSql(
                        "insert into test_min values (1, true, NULL, '2', 1, 1.11, 1.2, 1.3, '2021-08-04 16:26:33.4','2021-08-04', 'data1'), "
                                + "(1, false, NULL, 'b', 2, 2.22, 2.3, 2.4, '2021-08-06 16:26:33.4','2021-08-07', 'data2'), "
                                + "(2, false, NULL, '4', 1, 3.33, 3.5, 3.6, '2021-08-08 16:26:33.4','2021-08-08', 'data3'), "
                                + "(2, true, NULL, NULL, 4, 4.45, 4.7, 4.8, '2021-08-10 16:26:33.4','2021-08-01', 'data4')")
                .await();

        // test min with all elements are null
        List<Row> result =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(x) from test_min").collect());
        assertThat(result.toString()).isEqualTo("[+I[null]]");

        // test min with some elements are null
        List<Row> result2 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(y) from test_min").collect());
        assertThat(result2.toString()).isEqualTo("[+I[2]]");

        // test min with some elements repeated
        List<Row> result3 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(z) from test_min").collect());
        assertThat(result3.toString()).isEqualTo("[+I[1]]");

        // test min with decimal type
        List<Row> result4 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(d) from test_min").collect());
        assertThat(result4.toString()).isEqualTo("[+I[1.11000]]");

        // test min with float type
        List<Row> result5 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(e) from test_min").collect());
        assertThat(result5.toString()).isEqualTo("[+I[1.2]]");

        // test min with double type
        List<Row> result6 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(f) from test_min").collect());
        assertThat(result6.toString()).isEqualTo("[+I[1.3]]");

        // test min with boolean type
        List<Row> result7 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(b) from test_min").collect());
        assertThat(result7.toString()).isEqualTo("[+I[false]]");

        // test min with timestamp type
        List<Row> result8 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(ts) from test_min").collect());
        assertThat(result8.toString()).isEqualTo("[+I[2021-08-04T16:26:33.400]]");

        // test min with date type
        List<Row> result9 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(dt) from test_min").collect());
        assertThat(result9.toString()).isEqualTo("[+I[2021-08-01]]");

        // test min with binary type
        List<Row> result10 =
                CollectionUtil.iteratorToList(
                        tableEnv.executeSql("select min(bar) from test_min").collect());
        assertThat(result10.toString()).isEqualTo("[+I[[100, 97, 116, 97, 49]]]");

        tableEnv.executeSql("drop table test_min");

        // test min with unsupported data type
        tableEnv.executeSql(
                "create table test_min_not_support_type(a array<int>,m map<int, string>,s struct<f1:int,f2:string>)");
        // test min with row type
        String expectedRowMessage =
                "Hive native min aggregate function does not support type: 'ROW' now. Please re-check the data type.";
        assertSqlException(
                "select min(s) from test_min_not_support_type",
                TableException.class,
                expectedRowMessage);

        // test min with array type
        String expectedArrayMessage =
                "Hive native min aggregate function does not support type: 'ARRAY' now. Please re-check the data type.";
        assertSqlException(
                "select min(a) from test_min_not_support_type",
                TableException.class,
                expectedArrayMessage);

        // test min with map type, hive also does not support map type comparisons.
        String expectedMapMessage =
                "Cannot support comparison of map<> type or complex type containing map<>.";
        assertSqlException(
                "select min(m) from test_min_not_support_type",
                UDFArgumentTypeException.class,
                expectedMapMessage);

        tableEnv.executeSql("drop table test_min_not_support_type");
    }

    private void assertSqlException(
            String sql, Class<?> expectedExceptionClz, String expectedMessage) {
        assertThatThrownBy(() -> tableEnv.executeSql(sql))
                .rootCause()
                .isInstanceOf(expectedExceptionClz)
                .hasMessage(expectedMessage);
    }

    private static TableEnvironment getTableEnvWithHiveCatalog() {
        TableEnvironment tableEnv = HiveTestUtils.createTableEnvInBatchMode(SqlDialect.HIVE);
        tableEnv.registerCatalog(hiveCatalog.getName(), hiveCatalog);
        tableEnv.useCatalog(hiveCatalog.getName());
        // automatically load hive module in hive-compatible mode
        HiveModule hiveModule =
                new HiveModule(
                        hiveCatalog.getHiveVersion(),
                        tableEnv.getConfig(),
                        Thread.currentThread().getContextClassLoader());
        CoreModule coreModule = CoreModule.INSTANCE;
        for (String loaded : tableEnv.listModules()) {
            tableEnv.unloadModule(loaded);
        }
        tableEnv.loadModule("hive", hiveModule);
        tableEnv.loadModule("core", coreModule);
        return tableEnv;
    }
}
