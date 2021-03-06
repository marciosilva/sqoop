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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.lib.FieldFormatter;
import org.apache.hadoop.sqoop.lib.RecordParser;
import org.apache.hadoop.sqoop.manager.MySQLUtils;
import org.apache.hadoop.sqoop.util.AsyncSink;
import org.apache.hadoop.sqoop.util.DirectImportUtils;
import org.apache.hadoop.sqoop.util.ErrorableAsyncSink;
import org.apache.hadoop.sqoop.util.ErrorableThread;
import org.apache.hadoop.sqoop.util.ImportException;
import org.apache.hadoop.sqoop.util.JdbcUrl;
import org.apache.hadoop.sqoop.util.LoggingAsyncSink;
import org.apache.hadoop.sqoop.util.PerfCounters;

/**
 * Mapper that opens up a pipe to mysqldump and pulls data directly.
 */
public class MySQLDumpMapper
    extends Mapper<String, NullWritable, String, NullWritable> {

  public static final Log LOG = LogFactory.getLog(MySQLDumpMapper.class.getName());

  private Configuration conf;

  // AsyncSinks used to import data from mysqldump directly into HDFS.

  /**
   * Copies data directly from mysqldump into HDFS, after stripping some
   * header and footer characters that are attached to each line in mysqldump.
   */
  static class CopyingAsyncSink extends ErrorableAsyncSink {
    private final MySQLDumpMapper.Context context;
    private final PerfCounters counters;

    CopyingAsyncSink(final MySQLDumpMapper.Context context,
        final PerfCounters ctrs) {
      this.context = context;
      this.counters = ctrs;
    }

    public void processStream(InputStream is) {
      child = new CopyingStreamThread(is, context, counters);
      child.start();
    }

    private static class CopyingStreamThread extends ErrorableThread {
      public static final Log LOG = LogFactory.getLog(
          CopyingStreamThread.class.getName());

      private final MySQLDumpMapper.Context context;
      private final InputStream stream;
      private final PerfCounters counters;

      CopyingStreamThread(final InputStream is,
          final Context c, final PerfCounters ctrs) {
        this.context = c;
        this.stream = is;
        this.counters = ctrs;
      }

      public void run() {
        BufferedReader r = null;

        try {
          r = new BufferedReader(new InputStreamReader(this.stream));

          // Actually do the read/write transfer loop here.
          int preambleLen = -1; // set to this for "undefined"
          while (true) {
            String inLine = r.readLine();
            if (null == inLine) {
              break; // EOF.
            }

            // this line is of the form "INSERT .. VALUES ( actual value text );"
            // strip the leading preamble up to the '(' and the trailing ');'.
            if (preambleLen == -1) {
              // we haven't determined how long the preamble is. It's constant
              // across all lines, so just figure this out once.
              String recordStartMark = "VALUES (";
              preambleLen = inLine.indexOf(recordStartMark) + recordStartMark.length();
            }

            // chop off the leading and trailing text as we write the
            // output to HDFS.
            int len = inLine.length() - 2 - preambleLen;
            context.write(inLine.substring(preambleLen, inLine.length() - 2), null);
            context.write("\n", null);
            counters.addBytes(1 + len);
          }
        } catch (IOException ioe) {
          LOG.error("IOException reading from mysqldump: " + ioe.toString());
          // flag this error so we get an error status back in the caller.
          setError();
        } catch (InterruptedException ie) {
          LOG.error("InterruptedException reading from mysqldump: "
              + ie.toString());
          // flag this error so we get an error status back in the caller.
          setError();
        } finally {
          if (null != r) {
            try {
              r.close();
            } catch (IOException ioe) {
              LOG.info("Error closing FIFO stream: " + ioe.toString());
            }
          }
        }
      }
    }
  }


  /**
   * The ReparsingAsyncSink will instantiate a RecordParser to read mysqldump's
   * output, and re-emit the text in the user's specified output format.
   */
  static class ReparsingAsyncSink extends ErrorableAsyncSink {
    private final MySQLDumpMapper.Context context;
    private final Configuration conf;
    private final PerfCounters counters;

    ReparsingAsyncSink(final MySQLDumpMapper.Context c,
        final Configuration conf, final PerfCounters ctrs) {
      this.context = c;
      this.conf = conf;
      this.counters = ctrs;
    }

    public void processStream(InputStream is) {
      child = new ReparsingStreamThread(is, context, conf, counters);
      child.start();
    }

    private static class ReparsingStreamThread extends ErrorableThread {
      public static final Log LOG = LogFactory.getLog(
          ReparsingStreamThread.class.getName());

      private final MySQLDumpMapper.Context context;
      private final Configuration conf;
      private final InputStream stream;
      private final PerfCounters counters;

      ReparsingStreamThread(final InputStream is,
          final MySQLDumpMapper.Context c, Configuration conf,
          final PerfCounters ctrs) {
        this.context = c;
        this.conf = conf;
        this.stream = is;
        this.counters = ctrs;
      }

      private static final char MYSQL_FIELD_DELIM = ',';
      private static final char MYSQL_RECORD_DELIM = '\n';
      private static final char MYSQL_ENCLOSE_CHAR = '\'';
      private static final char MYSQL_ESCAPE_CHAR = '\\';
      private static final boolean MYSQL_ENCLOSE_REQUIRED = false;

      private static final RecordParser MYSQLDUMP_PARSER;

      static {
        // build a record parser for mysqldump's format
        MYSQLDUMP_PARSER = new RecordParser(MYSQL_FIELD_DELIM,
            MYSQL_RECORD_DELIM, MYSQL_ENCLOSE_CHAR, MYSQL_ESCAPE_CHAR,
            MYSQL_ENCLOSE_REQUIRED);
      }

      public void run() {
        BufferedReader r = null;

        try {
          r = new BufferedReader(new InputStreamReader(this.stream));

          char outputFieldDelim = (char) conf.getInt(
              MySQLUtils.OUTPUT_FIELD_DELIM_KEY, '\000');
          String outputFieldDelimStr = "" + outputFieldDelim;
          char outputRecordDelim = (char) conf.getInt(
              MySQLUtils.OUTPUT_RECORD_DELIM_KEY, '\000');
          String outputRecordDelimStr = "" + outputRecordDelim;
          char outputEnclose = (char) conf.getInt(
              MySQLUtils.OUTPUT_ENCLOSED_BY_KEY,
              '\000');
          String outputEncloseStr = "" + outputEnclose;
          char outputEscape = (char) conf.getInt(
              MySQLUtils.OUTPUT_ESCAPED_BY_KEY, '\000');
          String outputEscapeStr = "" + outputEscape;
          boolean outputEncloseRequired = conf.getBoolean(
              MySQLUtils.OUTPUT_ENCLOSE_REQUIRED_KEY, false);
          char [] encloseFor = { outputFieldDelim, outputRecordDelim };

          // Actually do the read/write transfer loop here.
          int preambleLen = -1; // set to this for "undefined"
          while (true) {
            String inLine = r.readLine();
            if (null == inLine) {
              break; // EOF.
            }

            // this line is of the form "INSERT .. VALUES ( actual value text );"
            // strip the leading preamble up to the '(' and the trailing ');'.
            if (preambleLen == -1) {
              // we haven't determined how long the preamble is. It's constant
              // across all lines, so just figure this out once.
              String recordStartMark = "VALUES (";
              preambleLen = inLine.indexOf(recordStartMark) + recordStartMark.length();
            }

            // Wrap the input string in a char buffer that ignores the leading and trailing
            // text.
            CharBuffer charbuf = CharBuffer.wrap(inLine, preambleLen, inLine.length() - 2);

            // Pass this along to the parser
            List<String> fields = null;
            try {
              fields = MYSQLDUMP_PARSER.parseRecord(charbuf);
            } catch (RecordParser.ParseError pe) {
              LOG.warn("ParseError reading from mysqldump: "
                  + pe.toString() + "; record skipped");
              continue; // Skip emitting this row.
            }

            // For all of the output fields, emit them using the delimiters the user chooses.
            boolean first = true;
            int recordLen = 1; // for the delimiter.
            for (String field : fields) {
              if (!first) {
                context.write(outputFieldDelimStr, null);
              } else {
                first = false;
              }

              String fieldStr = FieldFormatter.escapeAndEnclose(field,
                  outputEscapeStr, outputEncloseStr,
                  encloseFor, outputEncloseRequired);
              context.write(fieldStr, null);
              recordLen += fieldStr.length();
            }

            context.write(outputRecordDelimStr, null);
            counters.addBytes(recordLen);
          }
        } catch (IOException ioe) {
          LOG.error("IOException reading from mysqldump: " + ioe.toString());
          // flag this error so the parent can handle it appropriately.
          setError();
        } catch (InterruptedException ie) {
          LOG.error("InterruptedException reading from mysqldump: "
              + ie.toString());
          // flag this error so we get an error status back in the caller.
          setError();
        } finally {
          if (null != r) {
            try {
              r.close();
            } catch (IOException ioe) {
              LOG.info("Error closing FIFO stream: " + ioe.toString());
            }
          }
        }
      }
    }
  }

  /**
   * Import the table into HDFS by using mysqldump to pull out the data from
   * the database and upload the files directly to HDFS.
   */
  public void map(String splitConditions, NullWritable val, Context context)
      throws IOException, InterruptedException {

    LOG.info("Beginning mysqldump fast path import");

    ArrayList<String> args = new ArrayList<String>();
    String tableName = conf.get(MySQLUtils.TABLE_NAME_KEY);

    // We need to parse the connect string URI to determine the database
    // name. Using java.net.URL directly on the connect string will fail because
    // Java doesn't respect arbitrary JDBC-based schemes. So we chop off the scheme
    // (everything before '://') and replace it with 'http', which we know will work.
    String connectString = conf.get(MySQLUtils.CONNECT_STRING_KEY);
    String databaseName = JdbcUrl.getDatabaseName(connectString);
    String hostname = JdbcUrl.getHostName(connectString);
    int port = JdbcUrl.getPort(connectString);

    if (null == databaseName) {
      throw new IOException("Could not determine database name");
    }

    LOG.info("Performing import of table " + tableName + " from database "
        + databaseName);

    args.add(MySQLUtils.MYSQL_DUMP_CMD); // requires that this is on the path.

    String password = conf.get(MySQLUtils.PASSWORD_KEY);
    String passwordFile = null;

    Process p = null;
    AsyncSink sink = null;
    AsyncSink errSink = null;
    PerfCounters counters = new PerfCounters();
    try {
      // --defaults-file must be the first argument.
      if (null != password && password.length() > 0) {
        passwordFile = MySQLUtils.writePasswordFile(conf);
        args.add("--defaults-file=" + passwordFile);
      }

      // Don't use the --where="<whereClause>" version because spaces in it can
      // confuse Java, and adding in surrounding quotes confuses Java as well.
      String whereClause = conf.get(MySQLUtils.WHERE_CLAUSE_KEY, "(1=1)")
          + " AND (" + splitConditions + ")";
      args.add("-w");
      args.add(whereClause);

      args.add("--host=" + hostname);
      if (-1 != port) {
        args.add("--port=" + Integer.toString(port));
      }
      args.add("--skip-opt");
      args.add("--compact");
      args.add("--no-create-db");
      args.add("--no-create-info");
      args.add("--quick"); // no buffering
      args.add("--single-transaction");

      String username = conf.get(MySQLUtils.USERNAME_KEY);
      if (null != username) {
        args.add("--user=" + username);
      }

      // If the user supplied extra args, add them here.
      String [] extra = conf.getStrings(MySQLUtils.EXTRA_ARGS_KEY);
      if (null != extra) {
        for (String arg : extra) {
          args.add(arg);
        }
      }

      args.add(databaseName);
      args.add(tableName);

      // begin the import in an external process.
      LOG.debug("Starting mysqldump with arguments:");
      for (String arg : args) {
        LOG.debug("  " + arg);
      }

      // Actually start the mysqldump.
      p = Runtime.getRuntime().exec(args.toArray(new String[0]));

      // read from the stdout pipe into the HDFS writer.
      InputStream is = p.getInputStream();

      if (MySQLUtils.outputDelimsAreMySQL(conf)) {
        LOG.debug("Output delimiters conform to mysqldump; using straight copy"); 
        sink = new CopyingAsyncSink(context, counters);
      } else {
        LOG.debug("User-specified delimiters; using reparsing import");
        LOG.info("Converting data to use specified delimiters.");
        LOG.info("(For the fastest possible import, use");
        LOG.info("--mysql-delimiters to specify the same field");
        LOG.info("delimiters as are used by mysqldump.)");
        sink = new ReparsingAsyncSink(context, conf, counters);
      }

      // Start an async thread to read and upload the whole stream.
      counters.startClock();
      sink.processStream(is);

      // Start an async thread to send stderr to log4j.
      errSink = new LoggingAsyncSink(LOG);
      errSink.processStream(p.getErrorStream());
    } finally {

      // block until the process is done.
      int result = 0;
      if (null != p) {
        while (true) {
          try {
            result = p.waitFor();
          } catch (InterruptedException ie) {
            // interrupted; loop around.
            continue;
          }

          break;
        }
      }

      // Remove the password file.
      if (null != passwordFile) {
        if (!new File(passwordFile).delete()) {
          LOG.error("Could not remove mysql password file " + passwordFile);
          LOG.error("You should remove this file to protect your credentials.");
        }
      }

      // block until the stream sink is done too.
      int streamResult = 0;
      if (null != sink) {
        while (true) {
          try {
            streamResult = sink.join();
          } catch (InterruptedException ie) {
            // interrupted; loop around.
            continue;
          }

          break;
        }
      }

      // Try to wait for stderr to finish, but regard any errors as advisory.
      if (null != errSink) {
        try {
          if (0 != errSink.join()) {
            LOG.info("Encountered exception reading stderr stream");
          }
        } catch (InterruptedException ie) {
          LOG.info("Thread interrupted waiting for stderr to complete: "
              + ie.toString());
        }
      }

      LOG.info("Transfer loop complete.");

      if (0 != result) {
        throw new IOException("mysqldump terminated with status "
            + Integer.toString(result));
      }

      if (0 != streamResult) {
        throw new IOException("Encountered exception in stream sink");
      }

      counters.stopClock();
      LOG.info("Transferred " + counters.toString());
    }
  }

  @Override
  protected void setup(Context context) {
    this.conf = context.getConfiguration();
  }
}

