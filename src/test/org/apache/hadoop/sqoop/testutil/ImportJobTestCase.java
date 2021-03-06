/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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

package org.apache.hadoop.sqoop.testutil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.Sqoop;
import org.apache.hadoop.sqoop.SqoopOptions.InvalidOptionsException;
import org.apache.hadoop.sqoop.orm.CompilationManager;
import org.apache.hadoop.sqoop.util.ClassLoaderStack;

import org.junit.Test;

/**
 * Class that implements common methods required for tests which import data
 * from SQL into HDFS and verify correct import.
 */
public class ImportJobTestCase extends BaseSqoopTestCase {

  public static final Log LOG = LogFactory.getLog(ImportJobTestCase.class.getName());

  protected String getTablePrefix() {
    return "IMPORT_TABLE_";
  }

  /**
   * @return a list of additional args to pass to the sqoop command line.
   */
  protected List<String> getExtraArgs(Configuration conf) {
    return new ArrayList<String>();
  }

  /**
   * Create the argv to pass to Sqoop
   * @param includeHadoopFlags if true, then include -D various.settings=values
   * @param colNames the columns to import. If null, all columns are used.
   * @param conf a Configuration specifying additional properties to use when
   * determining the arguments.
   * @return the argv as an array of strings.
   */
  protected String [] getArgv(boolean includeHadoopFlags, String [] colNames,
      Configuration conf) {
    if (null == colNames) {
      colNames = getColNames();
    }

    String splitByCol = colNames[0];
    String columnsString = "";
    for (String col : colNames) {
      columnsString += col + ",";
    }

    ArrayList<String> args = new ArrayList<String>();

    if (includeHadoopFlags) {
      CommonArgs.addHadoopFlags(args);
    }

    args.add("--table");
    args.add(getTableName());
    args.add("--columns");
    args.add(columnsString);
    args.add("--split-by");
    args.add(splitByCol);
    args.add("--warehouse-dir");
    args.add(getWarehouseDir());
    args.add("--connect");
    args.add(getConnectString());
    args.add("--as-sequencefile");
    args.add("--num-mappers");
    args.add("2");

    args.addAll(getExtraArgs(conf));

    return args.toArray(new String[0]);
  }

  /**
   * Do a MapReduce-based import of the table and verify that the results
   * were imported as expected. (tests readFields(ResultSet) and toString())
   * @param expectedVal the value we injected into the table.
   * @param importCols the columns to import. If null, all columns are used.
   */
  protected void verifyImport(String expectedVal, String [] importCols) {

    // paths to where our output file will wind up.
    Path dataFilePath = getDataFilePath();

    removeTableDir();

    Configuration conf = getConf();
    SqoopOptions opts = getSqoopOptions(conf);

    // run the tool through the normal entry-point.
    int ret;
    try {
      Sqoop importer = new Sqoop(conf, opts);
      ret = ToolRunner.run(importer, getArgv(true, importCols, conf));
    } catch (Exception e) {
      LOG.error("Got exception running Sqoop: " + e.toString());
      throw new RuntimeException(e);
    }

    // expect a successful return.
    assertEquals("Failure during job", 0, ret);

    opts = getSqoopOptions(conf);
    try {
      opts.parse(getArgv(false, importCols, conf));
    } catch (InvalidOptionsException ioe) {
      fail(ioe.toString());
    }
    CompilationManager compileMgr = new CompilationManager(opts);
    String jarFileName = compileMgr.getJarFilename();
    ClassLoader prevClassLoader = null;
    try {
      prevClassLoader = ClassLoaderStack.addJarFile(jarFileName, getTableName());

      // now actually open the file and check it
      File f = new File(dataFilePath.toString());
      assertTrue("Error: " + dataFilePath.toString() + " does not exist", f.exists());

      Object readValue = SeqFileReader.getFirstValue(dataFilePath.toString());
      LOG.info("Read back from sequencefile: " + readValue);
      // add trailing '\n' to expected value since SqoopRecord.toString() encodes the record delim
      if (null == expectedVal) {
        assertEquals("Error validating result from SeqFile", "null\n", readValue.toString());
      } else {
        assertEquals("Error validating result from SeqFile", expectedVal + "\n",
            readValue.toString());
      }
    } catch (IOException ioe) {
      fail("IOException: " + ioe.toString());
    } finally {
      if (null != prevClassLoader) {
        ClassLoaderStack.setCurrentClassLoader(prevClassLoader);
      }
    }
  }

  /**
   * Run a MapReduce-based import (using the argv provided to control execution).
   */
  protected void runImport(String [] argv) throws IOException {
    removeTableDir();

    // run the tool through the normal entry-point.
    int ret;
    try {
      Configuration conf = getConf();
      SqoopOptions opts = getSqoopOptions(conf);
      Sqoop importer = new Sqoop(conf, opts);
      ret = ToolRunner.run(importer, argv);
    } catch (Exception e) {
      LOG.error("Got exception running Sqoop: " + e.toString());
      e.printStackTrace();
      ret = 1;
    }

    // expect a successful return.
    if (0 != ret) {
      throw new IOException("Failure during job; return status " + ret);
    }
  }

}
