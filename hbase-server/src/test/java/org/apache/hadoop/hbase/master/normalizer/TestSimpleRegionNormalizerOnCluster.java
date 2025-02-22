/**
 *
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
package org.apache.hadoop.hbase.master.normalizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.TableNamespaceManager;
import org.apache.hadoop.hbase.master.normalizer.NormalizationPlan.PlanType;
import org.apache.hadoop.hbase.namespace.TestNamespaceAuditor;
import org.apache.hadoop.hbase.quotas.QuotaUtil;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.test.LoadTestKVGenerator;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Testing {@link SimpleRegionNormalizer} on minicluster.
 */
@Category({MasterTests.class, MediumTests.class})
public class TestSimpleRegionNormalizerOnCluster {
  private static final Log LOG = LogFactory.getLog(TestSimpleRegionNormalizerOnCluster.class);
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final byte[] FAMILYNAME = Bytes.toBytes("fam");
  private static Admin admin;

  @BeforeClass
  public static void beforeAllTests() throws Exception {
    // we will retry operations when PleaseHoldException is thrown
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 3);
    TEST_UTIL.getConfiguration().setBoolean(HConstants.HBASE_NORMALIZER_ENABLED, true);
    TEST_UTIL.getConfiguration().setBoolean(QuotaUtil.QUOTA_CONF_KEY, true);

    // Start a cluster of two regionservers.
    TEST_UTIL.startMiniCluster(1);
    TestNamespaceAuditor.waitForQuotaEnabled(TEST_UTIL);
    admin = TEST_UTIL.getHBaseAdmin();
  }

  @AfterClass
  public static void afterAllTests() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test(timeout = 90000)
  @SuppressWarnings("deprecation")
  public void testRegionNormalizationSplitOnCluster() throws Exception {
    testRegionNormalizationSplitOnCluster(false);
    testRegionNormalizationSplitOnCluster(true);
  }

  void testRegionNormalizationSplitOnCluster(boolean limitedByQuota) throws Exception {
    TableName TABLENAME;
    if (limitedByQuota) {
      String nsp = "np2";
      NamespaceDescriptor nspDesc =
          NamespaceDescriptor.create(nsp)
          .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "5")
          .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2").build();
      admin.createNamespace(nspDesc);
      TABLENAME = TableName.valueOf(nsp +
        TableName.NAMESPACE_DELIM + "testRegionNormalizationSplitOnCluster");
    } else {
      TABLENAME = TableName.valueOf("testRegionNormalizationSplitOnCluster");
    }
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    HMaster m = cluster.getMaster();

    try (HTable ht = TEST_UTIL.createMultiRegionTable(TABLENAME, FAMILYNAME, 5)) {
      // Need to get sorted list of regions here
      List<HRegion> generatedRegions = TEST_UTIL.getHBaseCluster().getRegions(TABLENAME);
      Collections.sort(generatedRegions, new Comparator<HRegion>() {
        @Override
        public int compare(HRegion o1, HRegion o2) {
          return o1.getRegionInfo().compareTo(o2.getRegionInfo());
        }
      });

      HRegion region = generatedRegions.get(0);
      generateTestData(region, 1);
      region.flush(true);

      region = generatedRegions.get(1);
      generateTestData(region, 1);
      region.flush(true);

      region = generatedRegions.get(2);
      generateTestData(region, 2);
      region.flush(true);

      region = generatedRegions.get(3);
      generateTestData(region, 2);
      region.flush(true);

      region = generatedRegions.get(4);
      generateTestData(region, 5);
      region.flush(true);
    }

    HTableDescriptor htd = admin.getTableDescriptor(TABLENAME);
    htd.setNormalizationEnabled(true);
    admin.modifyTable(TABLENAME, htd);

    admin.flush(TABLENAME);

    assertEquals(5, MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME));

    // Now trigger a split and stop when the split is in progress
    Thread.sleep(5000); // to let region load to update
    m.normalizeRegions();
    if (limitedByQuota) {
      long skippedSplitcnt = 0;
      do {
        skippedSplitcnt = m.getRegionNormalizer().getSkippedCount(PlanType.SPLIT);
        Thread.sleep(100);
      } while (skippedSplitcnt == 0L);
      assert(skippedSplitcnt > 0);
    } else {
      while (MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME) < 6) {
        LOG.info("Waiting for normalization split to complete");
        Thread.sleep(100);
      }
      assertEquals(6, MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME));
    }

    admin.disableTable(TABLENAME);
    admin.deleteTable(TABLENAME);
  }

  @Test(timeout = 60000)
  @SuppressWarnings("deprecation")
  public void testRegionNormalizationMergeOnCluster() throws Exception {
    final TableName TABLENAME =
      TableName.valueOf("testRegionNormalizationMergeOnCluster");
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    HMaster m = cluster.getMaster();

    // create 5 regions with sizes to trigger merge of small regions
    try (HTable ht = TEST_UTIL.createMultiRegionTable(TABLENAME, FAMILYNAME, 5)) {
      // Need to get sorted list of regions here
      List<HRegion> generatedRegions = TEST_UTIL.getHBaseCluster().getRegions(TABLENAME);
      Collections.sort(generatedRegions, new Comparator<HRegion>() {
        @Override
        public int compare(HRegion o1, HRegion o2) {
          return o1.getRegionInfo().compareTo(o2.getRegionInfo());
        }
      });

      HRegion region = generatedRegions.get(0);
      generateTestData(region, 1);
      region.flush(true);

      region = generatedRegions.get(1);
      generateTestData(region, 1);
      region.flush(true);

      region = generatedRegions.get(2);
      generateTestData(region, 3);
      region.flush(true);

      region = generatedRegions.get(3);
      generateTestData(region, 3);
      region.flush(true);

      region = generatedRegions.get(4);
      generateTestData(region, 5);
      region.flush(true);
    }

    HTableDescriptor htd = admin.getTableDescriptor(TABLENAME);
    htd.setNormalizationEnabled(true);
    admin.modifyTable(TABLENAME, htd);

    admin.flush(TABLENAME);

    assertEquals(5, MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME));

    // Now trigger a merge and stop when the merge is in progress
    Thread.sleep(5000); // to let region load to update
    m.normalizeRegions();

    while (MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME) > 4) {
      LOG.info("Waiting for normalization merge to complete");
      Thread.sleep(100);
    }

    assertEquals(4, MetaTableAccessor.getRegionCount(TEST_UTIL.getConnection(), TABLENAME));

    admin.disableTable(TABLENAME);
    admin.deleteTable(TABLENAME);
  }

  private void generateTestData(Region region, int numRows) throws IOException {
    // generating 1Mb values
    LoadTestKVGenerator dataGenerator = new LoadTestKVGenerator(1024 * 1024, 1024 * 1024);
    for (int i = 0; i < numRows; ++i) {
      byte[] key = Bytes.add(region.getRegionInfo().getStartKey(), Bytes.toBytes(i));
      for (int j = 0; j < 1; ++j) {
        Put put = new Put(key);
        byte[] col = Bytes.toBytes(String.valueOf(j));
        byte[] value = dataGenerator.generateRandomSizeValue(key, col);
        put.addColumn(FAMILYNAME, col, value);
        region.put(put);
      }
    }
  }
}
