package com.dtstack.rdos.engine.execution.flink120.sink.hdfs;

import com.dtstack.rdos.common.util.ClassUtil;
import com.dtstack.rdos.common.util.DateUtil;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.types.Row;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.mapred.Reporter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by softfly on 17/7/3.
 */
public class HdfsOrcOutputFormat extends HdfsOutputFormat{

    private static final long serialVersionUID = 1L;

    private transient OrcSerde orcSerde;
    private transient StructObjectInspector inspector;
    private transient List<ObjectInspector> columnTypeList;


    @Override
    public void configure(Configuration configuration) {
        super.configure(configuration);

        this.orcSerde = new OrcSerde();
        this.outputFormat = new org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat();


        this.columnTypeList = new ArrayList<>();
        for(String columnType : columnTypes) {
            this.columnTypeList.add(HdfsUtil.columnTypeToObjectInspetor(columnType));
        }
        this.inspector = ObjectInspectorFactory
                .getStandardStructObjectInspector(Arrays.asList(this.columnNames), this.columnTypeList);

        Class<? extends CompressionCodec> codecClass = null;
        if(compress == null){
            codecClass = null;
        } else if("GZIP".equalsIgnoreCase(compress)){
            codecClass = org.apache.hadoop.io.compress.GzipCodec.class;
        } else if ("BZIP2".equalsIgnoreCase(compress)) {
            codecClass = org.apache.hadoop.io.compress.BZip2Codec.class;
        } else if("SNAPPY".equalsIgnoreCase(compress)) {
            //todo 等需求明确后支持 需要用户安装SnappyCodec
            codecClass = org.apache.hadoop.io.compress.SnappyCodec.class;
        } else {
            throw new IllegalArgumentException("Unsupported compress format: " + compress);
        }

        if(codecClass != null)
            this.outputFormat.setOutputCompressorClass(conf, codecClass);
    }

    @Override
    public void open(int taskNumber, int numTasks) throws IOException {
        if(taskNumber >= 0 && numTasks >= 1) {
            Date currentTime = new Date();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
            String dateString = formatter.format(currentTime);

            String pathStr = outputFilePath + "/" + taskNumber + "." + dateString + "." + UUID.randomUUID();
            System.out.println("pathStr=" + pathStr);

            this.recordWriter = this.outputFormat.getRecordWriter(null, conf, pathStr, Reporter.NULL);

        } else {
            throw new IllegalArgumentException("TaskNumber: " + taskNumber + ", numTasks: " + numTasks);
        }
    }

    @Override
    public void writeRecord(Row row) throws IOException {

        Object[] record = new Object[columnNames.length];
        for(int i = 0; i < row.getArity(); ++i) {
            Object column = row.getField(i);

            String columnName = inputColumnNames[i];
            String fromType = inputColumnTypes[i];
            String toType = columnNameTypeMap.get(columnName);

            if(toType == null) {
                continue;
            }

            if(!fromType.equalsIgnoreCase(toType)) {
                column = ClassUtil.convertType(column, fromType, toType);
            }

            String rowData = column.toString();
            Object field = null;
            switch(toType.toUpperCase()) {
                case "TINYINT":
                    field = Byte.valueOf(rowData);
                    break;
                case "SMALLINT":
                    field = Short.valueOf(rowData);
                    break;
                case "INT":
                    field = Integer.valueOf(rowData);
                    break;
                case "BIGINT":
                    field = Long.valueOf(rowData);
                    break;
                case "FLOAT":
                    field = Float.valueOf(rowData);
;                   break;
                case "DOUBLE":
                    field = Double.valueOf(rowData);
                    break;
                case "STRING":
                case "VARCHAR":
                case "CHAR":
                    field = rowData;
                    break;
                case "BOOLEAN":
                    field = Boolean.valueOf(rowData);
                    break;
                case "DATE":
                    field = DateUtil.columnToDate(column);
                    break;
                case "TIMESTAMP":
                    //recordList.add(new java.sql.Timestamp(column.asDate().getTime()));
                    java.sql.Date d = DateUtil.columnToDate(column);
                    field = new java.sql.Timestamp(d.getTime());
                    break;
                default:
                    throw new IllegalArgumentException();
            }

            record[columnNameIndexMap.get(columnName)] = field;

        }

        this.recordWriter.write(NullWritable.get(), this.orcSerde.serialize(Arrays.asList(record), this.inspector));
    }


}