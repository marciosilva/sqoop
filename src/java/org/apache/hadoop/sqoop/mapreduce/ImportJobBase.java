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

package org.apache.hadoop.sqoop.mapreduce;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.mapreduce.lib.db.DataDrivenDBInputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import org.apache.hadoop.sqoop.ConnFactory;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.manager.ConnManager;
import org.apache.hadoop.sqoop.orm.TableClassName;
import org.apache.hadoop.sqoop.shims.HadoopShim;
import org.apache.hadoop.sqoop.util.ClassLoaderStack;
import org.apache.hadoop.sqoop.util.ImportException;
import org.apache.hadoop.sqoop.util.PerfCounters;

/**
 * Base class for running an import MapReduce job.
 * Allows dependency injection, etc, for easy customization of import job types.
 */
public class ImportJobBase extends JobBase {

  public static final Log LOG = LogFactory.getLog(ImportJobBase.class.getName());

  public ImportJobBase() {
    this(null);
  }

  public ImportJobBase(final SqoopOptions opts) {
    this(opts, null, null, null);
  }

  public ImportJobBase(final SqoopOptions opts,
      final Class<? extends Mapper> mapperClass,
      final Class<? extends InputFormat> inputFormatClass,
      final Class<? extends OutputFormat> outputFormatClass) {
    super(opts, mapperClass, inputFormatClass, outputFormatClass);
  }

  /**
   * Configure the output format to use for the job.
   */
  @Override
  protected void configureOutputFormat(Job job, String tableName,
      String tableClassName) throws ClassNotFoundException, IOException {
    String hdfsWarehouseDir = options.getWarehouseDir();
    Path outputPath;

    if (null != hdfsWarehouseDir) {
      Path hdfsWarehousePath = new Path(hdfsWarehouseDir);
      hdfsWarehousePath.makeQualified(FileSystem.get(job.getConfiguration()));
      outputPath = new Path(hdfsWarehousePath, tableName);
    } else {
      outputPath = new Path(tableName);
    }

    job.setOutputFormatClass(getOutputFormatClass());

    if (options.getFileLayout() == SqoopOptions.FileLayout.SequenceFile) {
      job.getConfiguration().set("mapred.output.value.class", tableClassName);
    }

    if (options.shouldUseCompression()) {
      FileOutputFormat.setCompressOutput(job, true);
      FileOutputFormat.setOutputCompressorClass(job, GzipCodec.class);
      SequenceFileOutputFormat.setOutputCompressionType(job,
          CompressionType.BLOCK);
    }

    FileOutputFormat.setOutputPath(job, outputPath);
  }

  /**
   * Actually run the MapReduce job.
   */
  @Override
  protected boolean runJob(Job job) throws ClassNotFoundException, IOException,
      InterruptedException {

    PerfCounters counters = new PerfCounters();
    counters.startClock();

    boolean success = job.waitForCompletion(true);
    counters.stopClock();
    counters.addBytes(job.getCounters().getGroup("FileSystemCounters")
      .findCounter("HDFS_BYTES_WRITTEN").getValue());
    LOG.info("Transferred " + counters.toString());
    long numRecords = HadoopShim.get().getNumMapOutputRecords(job);
    LOG.info("Retrieved " + numRecords + " records.");
    return success;
  }


  /**
   * Run an import job to read a table in to HDFS
   *
   * @param tableName  the database table to read
   * @param ormJarFile the Jar file to insert into the dcache classpath. (may be null)
   * @param splitByCol the column of the database table to use to split the import
   * @param conf A fresh Hadoop Configuration to use to build an MR job.
   * @throws IOException if the job encountered an IO problem
   * @throws ImportException if the job failed unexpectedly or was misconfigured.
   */
  public void runImport(String tableName, String ormJarFile, String splitByCol,
      Configuration conf) throws IOException, ImportException {

    LOG.info("Beginning import of " + tableName);
    String tableClassName = new TableClassName(options).getClassForTable(tableName);
    loadJars(conf, ormJarFile, tableClassName);

    try {
      Job job = new Job(conf);

      // Set the external jar to use for the job.
      job.getConfiguration().set("mapred.jar", ormJarFile);

      configureInputFormat(job, tableName, tableClassName, splitByCol);
      configureOutputFormat(job, tableName, tableClassName);
      configureMapper(job, tableName, tableClassName);
      configureNumTasks(job);

      boolean success = runJob(job);
      if (!success) {
        throw new ImportException("Import job failed!");
      }
    } catch (InterruptedException ie) {
      throw new IOException(ie);
    } catch (ClassNotFoundException cnfe) {
      throw new IOException(cnfe);
    } finally {
      unloadJars();
    }
  }
}
