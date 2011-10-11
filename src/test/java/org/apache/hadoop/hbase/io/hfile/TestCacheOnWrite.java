/*
 * Copyright 2011 The Apache Software Foundation
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

package org.apache.hadoop.hbase.io.hfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileBlock;
import org.apache.hadoop.hbase.io.hfile.HFileBlockIndex;
import org.apache.hadoop.hbase.io.hfile.HFileReaderV2;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.TestHFileWriterV2;
import org.apache.hadoop.hbase.regionserver.CreateRandomStoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import static org.junit.Assert.*;

/**
 * Tests {@link HFile} cache-on-write functionality for the following block
 * types: data blocks, non-root index blocks, and Bloom filter blocks.
 */
@RunWith(Parameterized.class)
public class TestCacheOnWrite {

  private static final Log LOG = LogFactory.getLog(TestCacheOnWrite.class);

  private static final HBaseTestingUtility TEST_UTIL =
    new HBaseTestingUtility();
  private Configuration conf;
  private FileSystem fs;
  private Random rand = new Random(12983177L);
  private Path storeFilePath;
  private Compression.Algorithm compress;
  private CacheOnWriteType cowType;
  private BlockCache blockCache;
  private String testName;

  private static final int DATA_BLOCK_SIZE = 2048;
  private static final int NUM_KV = 25000;
  private static final int INDEX_BLOCK_SIZE = 512;
  private static final int BLOOM_BLOCK_SIZE = 4096;

  private static enum CacheOnWriteType {
    DATA_BLOCKS(BlockType.DATA, HFile.CACHE_BLOCKS_ON_WRITE_KEY),
    BLOOM_BLOCKS(BlockType.BLOOM_CHUNK,
        BloomFilterFactory.IO_STOREFILE_BLOOM_CACHE_ON_WRITE),
    INDEX_BLOCKS(BlockType.LEAF_INDEX,
        HFileBlockIndex.CACHE_INDEX_BLOCKS_ON_WRITE_KEY);

    private final String confKey;
    private final BlockType inlineBlockType;

    private CacheOnWriteType(BlockType inlineBlockType, String confKey) {
      this.inlineBlockType = inlineBlockType;
      this.confKey = confKey;
    }

    public boolean shouldBeCached(BlockType blockType) {
      return blockType == inlineBlockType
          || blockType == BlockType.INTERMEDIATE_INDEX
          && inlineBlockType == BlockType.LEAF_INDEX;
    }

    public void modifyConf(Configuration conf) {
      for (CacheOnWriteType cowType : CacheOnWriteType.values())
        conf.setBoolean(cowType.confKey, cowType == this);
    }

  }

  public TestCacheOnWrite(CacheOnWriteType cowType,
      Compression.Algorithm compress) {
    this.cowType = cowType;
    this.compress = compress;
    testName = "[cacheOnWrite=" + cowType + ", compress=" + compress + "]";
  }

  @Parameters
  public static Collection<Object[]> getParameters() {
    List<Object[]> cowTypes = new ArrayList<Object[]>();
    for (CacheOnWriteType cowType : CacheOnWriteType.values())
      for (Compression.Algorithm compress :
           HBaseTestingUtility.COMPRESSION_ALGORITHMS) {
        cowTypes.add(new Object[] { cowType, compress });
      }
    return cowTypes;
  }

  @Before
  public void setUp() throws IOException {
    conf = TEST_UTIL.getConfiguration();
    conf.setInt(HFile.FORMAT_VERSION_KEY, HFile.MAX_FORMAT_VERSION);
    conf.setInt(HFileBlockIndex.MAX_CHUNK_SIZE_KEY, INDEX_BLOCK_SIZE);
    conf.setInt(BloomFilterFactory.IO_STOREFILE_BLOOM_BLOCK_SIZE,
        BLOOM_BLOCK_SIZE);
    cowType.modifyConf(conf);
    fs = FileSystem.get(conf);
    blockCache = StoreFile.getBlockCache(conf);
  }

  @After
  public void tearDown() {
    blockCache.evictBlocksByPrefix("");
  }

  @Test
  public void testCacheOnWrite() throws IOException {
    writeStoreFile();
    readStoreFile();
  }

  private void readStoreFile() throws IOException {
    HFileReaderV2 reader = (HFileReaderV2) HFile.createReader(fs,
        storeFilePath, null, false, false);
    LOG.info("HFile information: " + reader);
    HFileScanner scanner = reader.getScanner(false, false);
    assertTrue(testName, scanner.seekTo());

    long offset = 0;
    HFileBlock prevBlock = null;
    EnumMap<BlockType, Integer> blockCountByType =
        new EnumMap<BlockType, Integer>(BlockType.class);

    while (offset < reader.getTrailer().getLoadOnOpenDataOffset()) {
      long onDiskSize = -1;
      if (prevBlock != null) {
         onDiskSize = prevBlock.getNextBlockOnDiskSizeWithHeader();
      }
      // Flags: cache the block, use pread, this is not a compaction.
      HFileBlock block = reader.readBlock(offset, onDiskSize, true, true,
          false);
      String blockCacheKey = HFile.getBlockCacheKey(reader.getName(), offset);
      boolean isCached = blockCache.getBlock(blockCacheKey, true) != null;
      boolean shouldBeCached = cowType.shouldBeCached(block.getBlockType());
      assertEquals(testName + " " + block, shouldBeCached, isCached);
      prevBlock = block;
      offset += block.getOnDiskSizeWithHeader();
      BlockType bt = block.getBlockType();
      Integer count = blockCountByType.get(bt);
      blockCountByType.put(bt, (count == null ? 0 : count) + 1);
    }

    LOG.info("Block count by type: " + blockCountByType);
    assertEquals(
        "{DATA=1367, LEAF_INDEX=172, BLOOM_CHUNK=9, INTERMEDIATE_INDEX=24}",
        blockCountByType.toString());

    reader.close();
  }

  public void writeStoreFile() throws IOException {
    Path storeFileParentDir = new Path(HBaseTestingUtility.getTestDir(),
        "test_cache_on_write");
    StoreFile.Writer sfw = StoreFile.createWriter(fs, storeFileParentDir,
        DATA_BLOCK_SIZE, compress, KeyValue.COMPARATOR, conf,
        StoreFile.BloomType.ROWCOL, NUM_KV);

    final int rowLen = 32;
    for (int i = 0; i < NUM_KV; ++i) {
      byte[] k = TestHFileWriterV2.randomOrderedKey(rand, i);
      byte[] v = TestHFileWriterV2.randomValue(rand);
      int cfLen = rand.nextInt(k.length - rowLen + 1);
      KeyValue kv = new KeyValue(
          k, 0, rowLen,
          k, rowLen, cfLen,
          k, rowLen + cfLen, k.length - rowLen - cfLen,
          rand.nextLong(),
          CreateRandomStoreFile.generateKeyType(rand),
          v, 0, v.length);
      sfw.append(kv);
    }

    sfw.close();
    storeFilePath = sfw.getPath();
  }

}