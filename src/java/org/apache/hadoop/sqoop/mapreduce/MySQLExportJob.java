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
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.mapreduce.lib.db.DataDrivenDBInputFormat;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;

import org.apache.hadoop.sqoop.ConnFactory;
import org.apache.hadoop.sqoop.manager.ConnManager;
import org.apache.hadoop.sqoop.manager.ExportJobContext;
import org.apache.hadoop.sqoop.manager.MySQLUtils;

/**
 * Class that runs an export job using mysqlimport in the mapper.
 */
public class MySQLExportJob extends ExportJobBase {

  public static final Log LOG =
      LogFactory.getLog(MySQLExportJob.class.getName());

  public MySQLExportJob(final ExportJobContext context) {
    super(context);
  }

  @Override
  protected Class<? extends OutputFormat> getOutputFormatClass() {
    // This job does not write to the OutputCollector. Disable it.
    return NullOutputFormat.class;
  }

  @Override
  /**
   * Configure the inputformat to use for the job.
   */
  protected void configureInputFormat(Job job, String tableName,
      String tableClassName, String splitByCol)
      throws ClassNotFoundException, IOException {

    // Configure the delimiters, etc.
    Configuration conf = job.getConfiguration();
    conf.setInt(MySQLUtils.OUTPUT_FIELD_DELIM_KEY,
        options.getOutputFieldDelim());
    conf.setInt(MySQLUtils.OUTPUT_RECORD_DELIM_KEY,
        options.getOutputRecordDelim());
    conf.setInt(MySQLUtils.OUTPUT_ENCLOSED_BY_KEY,
        options.getOutputEnclosedBy());
    conf.setInt(MySQLUtils.OUTPUT_ESCAPED_BY_KEY,
        options.getOutputEscapedBy());
    conf.setBoolean(MySQLUtils.OUTPUT_ENCLOSE_REQUIRED_KEY,
        options.isOutputEncloseRequired());
    String [] extraArgs = options.getExtraArgs();
    if (null != extraArgs) {
      conf.setStrings(MySQLUtils.EXTRA_ARGS_KEY, extraArgs);
    }

    ConnManager mgr = null;
    try {
      mgr = new ConnFactory(conf).getManager(options);
      String username = options.getUsername();
      if (null == username || username.length() == 0) {
        DBConfiguration.configureDB(job.getConfiguration(), mgr.getDriverClass(),
            options.getConnectString());
      } else {
        DBConfiguration.configureDB(job.getConfiguration(), mgr.getDriverClass(),
            options.getConnectString(), username, options.getPassword());
      }

      String [] colNames = options.getColumns();
      if (null == colNames) {
        colNames = mgr.getColumnNames(tableName);
      }

      String [] sqlColNames = null;
      if (null != colNames) {
        sqlColNames = new String[colNames.length];
        for (int i = 0; i < colNames.length; i++) {
          sqlColNames[i] = mgr.escapeColName(colNames[i]);
        }
      }

      // Note that mysqldump also does *not* want a quoted table name.
      DataDrivenDBInputFormat.setInput(job, DBWritable.class,
          tableName, null, null, sqlColNames);
    } finally {
      try {
        mgr.close();
      } catch (SQLException sqlE) {
        LOG.warn("Error closing connection manager: " + sqlE);
      }
    }

    // Configure the actual InputFormat to use. 
    super.configureInputFormat(job, tableName, tableClassName, splitByCol);
  }


  @Override
  protected Class<? extends Mapper> getMapperClass() {
    if (inputIsSequenceFiles()) {
      return MySQLRecordExportMapper.class;
    } else {
      return MySQLTextExportMapper.class;
    }
  }
}
