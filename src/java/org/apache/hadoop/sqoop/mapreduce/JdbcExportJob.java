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
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.mapreduce.lib.db.DBOutputFormat;

import org.apache.hadoop.sqoop.ConnFactory;
import org.apache.hadoop.sqoop.manager.ConnManager;
import org.apache.hadoop.sqoop.manager.ExportJobContext;


/**
 * Run an export using JDBC (DBOutputFormat)
 */
public class JdbcExportJob extends ExportJobBase {

  public static final Log LOG = LogFactory.getLog(
      JdbcExportJob.class.getName());

  public JdbcExportJob(final ExportJobContext context) {
    super(context);
  }

  @Override
  protected Class<? extends Mapper> getMapperClass() {
    if (inputIsSequenceFiles()) {
      return SequenceFileExportMapper.class;
    } else {
      return TextExportMapper.class;
    }
  }

  @Override
  protected void configureOutputFormat(Job job, String tableName,
      String tableClassName) throws IOException {

    Configuration conf = options.getConf();
    ConnManager mgr = new ConnFactory(conf).getManager(options);
    try {
      String username = options.getUsername();
      if (null == username || username.length() == 0) {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(),
            options.getConnectString());
      } else {
        DBConfiguration.configureDB(job.getConfiguration(),
            mgr.getDriverClass(),
            options.getConnectString(),
            username, options.getPassword());
      }

      String [] colNames = options.getColumns();
      if (null == colNames) {
        colNames = mgr.getColumnNames(tableName);
      }
      DBOutputFormat.setOutput(job, tableName, colNames);

      job.setOutputFormatClass(DBOutputFormat.class);
      job.getConfiguration().set(SQOOP_EXPORT_TABLE_CLASS_KEY, tableClassName);
    } finally {
      try {
        mgr.close();
      } catch (SQLException sqlE) {
        LOG.warn("Error closing connection: " + sqlE);
      }
    }
  }



}

