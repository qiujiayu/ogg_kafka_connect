package oracle.goldengate.kafkaconnect.formatter;

import oracle.goldengate.datasource.DsColumn;
import oracle.goldengate.datasource.DsConfiguration;
import oracle.goldengate.datasource.DsEvent;
import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsToken;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.GGDataSource.Status;
import oracle.goldengate.datasource.ObjectType;
import oracle.goldengate.datasource.format.NgFormatter;
import oracle.goldengate.datasource.format.NgUniqueTimestamp;
import oracle.goldengate.datasource.meta.ColumnMetaData;
import oracle.goldengate.datasource.meta.DsMetaData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.format.NgFormattedData;
import oracle.goldengate.kafkaconnect.DpConstants;

import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This formatted formats operations into Kafka Connect row operation and returns the
 * data as a Kafka Connect Source Record object.
 *
 * @author tbcampbe
 */
public class KafkaConnectFormatter implements NgFormatter {
    private static final Logger logger = LoggerFactory.getLogger(KafkaConnectFormatter.class);
    //The schema generator
    private KafkaConnectSchemaGenerator schemaGenerator;

    //The default operation keys
    private String insertOpKey = "I";        // inserts
    private String updateOpKey = "U";        // updates
    private String deleteOpKey = "D";        // deletes
    //The action to take if the handler encounters a primary key update
    private PkHandling pkHandling = PkHandling.PK_ABEND;
    //Optional functionality to treat all values as strings
    private boolean treatAllColumnsAsStrings = false;
    //Version Avro Schemas
    private boolean versionAvroSchemas = false;
    //Use ISO8601 format for current timestamp
    private boolean useIso8601Format = true;

    /**
     * Method to set the insert operation key.  This key will be included in the
     * output for insert operations.  The default is "I".
     *
     * @param opKey The insert operation key.
     */
    public void setInsertOpKey(String opKey) {
        insertOpKey = opKey;
    }

    /**
     * Method to set the update operation key.  This key will be included in the
     * output for update operations.  The default is "U".
     *
     * @param opKey The update operation key.
     */
    public void setUpdateOpKey(String opKey) {
        updateOpKey = opKey;
    }

    /**
     * Method to set the delete operation key.  This key will be included in the
     * output for delete operations.  The default is "D".
     *
     * @param opKey The delete operation key.
     */
    public void setDeleteOpKey(String opKey) {
        deleteOpKey = opKey;
    }

    /**
     * The default handling of the Kafka Connect formatter is to map column
     * values from the source definitions the best fit for a corresponding type.
     * Set this method to true to alternatively treat all columns as strings.
     * This feature is provided in Jackson which is where the idea came from.
     *
     * @param allColumnsAsStrings True to treat all columns as strings.
     */
    public void setTreatAllColumnsAsStrings(boolean allColumnsAsStrings) {
        treatAllColumnsAsStrings = allColumnsAsStrings;
    }

    /**
     * Method to set if to use the ISO-8601 format for the current data timestamp.
     *
     * @param iso8601 True to use ISO-8601 format, else false.
     */
    public void setIso8601Format(boolean iso8601) {
        useIso8601Format = iso8601;
    }

    /**
     * Method to set what action to take in the case of a PK update (primary
     * key update).  The default is to ABEND.  Set as follows:
     * abend - to abend
     * update - to treat as a normal update
     * delete-insert - to tream as a delete operation and then an insert operation.
     *
     * @param handling abend
     */
    public void setPkUpdateHandling(String handling) {
        if (PkHandling.PK_ABEND.compareAction(handling)) {
            pkHandling = PkHandling.PK_ABEND;
        } else if (PkHandling.PK_UPDATE.compareAction(handling)) {
            pkHandling = PkHandling.PK_UPDATE;
        } else if (PkHandling.PK_DELETE_INSERT.compareAction(handling)) {
            pkHandling = PkHandling.PK_DELETE_INSERT;
        } else {
            logger.warn("The value in the Kafka Connect Formatter of ["
                + handling
                + "] for primary key handling is not valid.  "
                + "The default behavior is to ABEND");
            pkHandling = PkHandling.PK_ABEND;
        }
    }

    @Override
    public NgFormattedData createNgFormattedData() {
        return new KafkaConnectFormattedData();
    }

    @Override
    public void init(DsConfiguration dc, DsMetaData dmd) {
        if (logger.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append(System.lineSeparator());
            sb.append("**** Begin Kafka Connect Row Formatter - Configuration Summary ****");
            sb.append(System.lineSeparator());

            sb.append("  Operation types are always included in the formatter output.");
            sb.append(System.lineSeparator());
            sb.append("    The key for insert operations is [");
            sb.append(insertOpKey);
            sb.append("].");
            sb.append(System.lineSeparator());
            sb.append("    The key for update operations is [");
            sb.append(updateOpKey);
            sb.append("].");
            sb.append(System.lineSeparator());
            sb.append("    The key for delete operations is [");
            sb.append(deleteOpKey);
            sb.append("].");
            sb.append(System.lineSeparator());
            //How will column type mapping
            if (treatAllColumnsAsStrings) {
                sb.append("  Column type mapping has been configured to treat all source columns as strings.");
            } else {
                sb.append("  Column type mapping has been configured to map source column types to an appropriate corresponding Kafka Connect Schema type.");
            }
            sb.append(System.lineSeparator());

            //How are primary key updates handled.
            if (pkHandling == PkHandling.PK_ABEND) {
                //Need to abend
                sb.append("  In the event of a primary key update, the Formatter will ABEND.");
            } else if (pkHandling == PkHandling.PK_UPDATE) {
                sb.append("  In the event of a primary key update, the Formatter will process it as a normal update.");
            } else if (pkHandling == PkHandling.PK_DELETE_INSERT) {
                sb.append("  In the event of a primary key update, the Formatter will process it as a delete and an insert.");
            }
            sb.append(System.lineSeparator());

            if (useIso8601Format) {
                sb.append("  The current timestamp will be in ISO-8601 format.");
            } else {
                sb.append("  The current timestamp will not be in ISO-8601 format.");
            }
            sb.append(System.lineSeparator());
            sb.append("**** End Kafka Connect Row Formatter - Configuration Summary ****");
            sb.append(System.lineSeparator());
            logger.info(sb.toString());
        }
        //Instantiate the schema generator.
        schemaGenerator = new KafkaConnectSchemaGenerator();
        schemaGenerator.setTreatAllColumnsAsStrings(treatAllColumnsAsStrings);
    }

    @Override
    public void beginTx(DsTransaction dt, DsMetaData dmd, NgFormattedData nfd) throws Exception {
        //NOOP
    }

    @Override
    public void formatOp(DsTransaction tx, DsOperation op, TableMetaData tMeta, NgFormattedData output) throws Exception {
        String tableName = tMeta.getTableName().getOriginalName();
        logger.debug("Entering formatOp");
        try {
            KafkaConnectFormattedData objectFormattedData = (KafkaConnectFormattedData) output;
            KeyAndPayloadSchemas schemas = schemaGenerator.getSchema(tableName, tMeta);

            Struct rec1 = new Struct(schemas.getPayloadSchema());
            Struct rec2 = null;
            Struct key1 = null;
            Struct key2 = null;
            if (schemas.getKeySchema() != null) {
                key1 = new Struct(schemas.getKeySchema());
            }
            formatOperationMetadata(op.getOperationType(), op, tMeta, rec1);
            Struct source = new Struct(rec1.schema().field("source").schema());
            formatPayloadSource(op, tMeta, source);
            rec1.put("source", source);

            if (op.getOperationType().isInsert()) {
                //Insert is after values
                Struct after = new Struct(rec1.schema().field("after").schema());
                formatAfterValuesOp(op.getOperationType(), tx, op, tMeta, after, key1);
                rec1.put("after", after);
            } else if (op.getOperationType().isDelete()) {
                //Delete is before values
                Struct before = new Struct(rec1.schema().field("before").schema());
                formatBeforeValuesOp(op.getOperationType(), tx, op, tMeta, before, key1);
                rec1.put("before", before);
            } else if (op.getOperationType().isPkUpdate()) {
                //Primary key updates are a special case of update and have
                //optional handling.
                if (pkHandling == PkHandling.PK_ABEND) {
                    //Need to abend
                    logger.error("The Kafka Connect Formatter encountered a update including a primary key.  The behavior is configured to ABEND in this scenario.");
                    throw new RuntimeException("The Kafka Connect Formatter encountered a update including a primary key.  The behavior is configured to ABEND in this scenario.");
                } else if (pkHandling == PkHandling.PK_UPDATE) {
                    Struct after = new Struct(rec1.schema().field("after").schema());
                    formatAfterValuesOp(DsOperation.OpType.DO_UPDATE, tx, op, tMeta, after, key1);
                    rec1.put("after", after);
                } else if (pkHandling == PkHandling.PK_DELETE_INSERT) {
                    Struct before = new Struct(rec1.schema().field("before").schema());
                    formatBeforeValuesOp(DsOperation.OpType.DO_DELETE, tx, op, tMeta, before, key1);
                    rec1.put("before", before);
                    rec2 = new Struct(schemas.getPayloadSchema());
                    if (schemas.getKeySchema() != null) {
                        key2 = new Struct(schemas.getKeySchema());
                    }
                    Struct after = new Struct(rec2.schema().field("after").schema());
                    formatAfterValuesOp(DsOperation.OpType.DO_INSERT, tx, op, tMeta, after, key2);
                    rec2.put("after", after);
                }
            } else if (op.getOperationType().isUpdate()) {
                //Update is after values
                Struct after = new Struct(rec1.schema().field("after").schema());
                formatAfterValuesOp(op.getOperationType(), tx, op, tMeta, after, key1);
                rec1.put("after", after);
            } else {
                //Unknown operation, log a warning and move on.
                logger.error("The Formatter encounted an unknown operation ["
                    + op.getOperationType() + "].");
                throw new RuntimeException("The Formatter encounted an unknown operation ["
                    + op.getOperationType() + "].");
            }
            //Set the objects
            objectFormattedData.setRecord(rec1);
            objectFormattedData.setKey(key1);
            //Only will be a second object if this is a primary key update
            objectFormattedData.setRecord(rec2);
            objectFormattedData.setKey(key2);

        } catch (Exception e) {
            logger.error("The Kafka Connect Row Formatter formatOp operation failed.", e);
            throw e;
        }
    }


    @Override
    public void endTx(DsTransaction dt, DsMetaData dmd, NgFormattedData nfd) throws Exception {
        //NOOP
    }

    @Override
    public void ddlOperation(DsOperation.OpType opType, ObjectType objectType, String objectName, String ddlText) throws Exception {
        // /This is where we should capture schema change for Schema Reg
        schemaGenerator.dropSchema(objectName);
    }

    private void formatBeforeValuesOp(DsOperation.OpType type, DsTransaction tx, DsOperation op,
                                      TableMetaData tmeta, Struct rec, Struct key) {
//        formatOperationMetadata(type, op, tmeta, rec);
        formatBeforeValues(tx, op, tmeta, rec);
        formatBeforeKeys(tx, op, tmeta, key);

    }

    private void formatAfterValuesOp(DsOperation.OpType type, DsTransaction tx, DsOperation op,
                                     TableMetaData tmeta, Struct rec, Struct key) {
//        formatOperationMetadata(type, op, tmeta, rec);
        formatAfterValues(tx, op, tmeta, rec);
        formatAfterKeys(tx, op, tmeta, key);


    }

    private void formatEmptyValuesOp(DsOperation.OpType type, DsTransaction tx, DsOperation op,
                                     TableMetaData tMeta, Struct rec) {
        formatOperationMetadata(type, op, tMeta, rec);
        //This is a truncate operation, it needs to column values
    }

    private void formatBeforeValues(DsTransaction tx, DsOperation op,
                                    TableMetaData tMeta, Struct rec) {
        int cIndex = 0;
        for (DsColumn col : op.getColumns()) {
            ColumnMetaData cMeta = tMeta.getColumnMetaData(cIndex++);
            DsColumn beforeCol = col.getBefore();
            //Only need to include a value if the before column object is not
            //null and the associated value is not null, this unmasks a
            //shortcoming of Avro formatter.  There is no difference between
            //a missing column and a null value.
            if ((beforeCol != null) && (!beforeCol.isValueNull())) {
                //The beforeCol object is NOT null
                formatColumnValue(cMeta, beforeCol, rec);
            }
        }
    }

    private void formatBeforeKeys(DsTransaction tx, DsOperation op,
                                  TableMetaData tmeta, Struct key) {
        if (key == null) {
            //In this case nothing to do.  Simply return.
            return;
        }
        int cIndex = 0;
        for (DsColumn col : op.getColumns()) {
            ColumnMetaData cmeta = tmeta.getColumnMetaData(cIndex++);
            if (cmeta.isKeyCol()) {
                //This is a primary key column
                DsColumn beforeCol = col.getBefore();
                if ((beforeCol != null) && (!beforeCol.isValueNull())) {
                    formatColumnValue(cmeta, beforeCol, key);
                }
            }
        }
    }

    private void formatAfterValues(DsTransaction tx, DsOperation op,
                                   TableMetaData tMeta, Struct rec) {
        int cIndex = 0;
        for (DsColumn col : op.getColumns()) {
            ColumnMetaData cMeta = tMeta.getColumnMetaData(cIndex++);
            DsColumn afterCol = col.getAfter();
            //Only need to include a value if the after column object is not
            //null and the associated value is not null, this unmasks a
            //shortcoming of Avro formatter.  There is no difference between
            //a missing column and a null value.
            if ((afterCol != null) && (!afterCol.isValueNull())) {
                //The afterCol object is NOT null
                formatColumnValue(cMeta, afterCol, rec);
            }
        }
    }

    private void formatAfterKeys(DsTransaction tx, DsOperation op,
                                 TableMetaData tmeta, Struct key) {
        if (key == null) {
            //In this case nothing to do.  Simply return.
            return;
        }
        int cIndex = 0;
        for (DsColumn col : op.getColumns()) {
            ColumnMetaData cmeta = tmeta.getColumnMetaData(cIndex++);
            if (cmeta.isKeyCol()) {
                //This is a primary key column
                DsColumn afterCol = col.getAfter();
                if ((afterCol != null) && (!afterCol.isValueNull())) {
                    formatColumnValue(cmeta, afterCol, key);
                }
            }
        }
    }

    protected void formatOperationMetadata(DsOperation.OpType type, DsOperation op,
                                           TableMetaData tMeta, Struct rec) {
        formatTableName(tMeta, rec);
        formatOpType(type, rec);
        formatOperationTimestamp(op, rec);
        formatCurrentTimestamp(rec);
        formatPosition(op, rec);
        //formatPrimaryKeys(tMeta, rec);
        //formatTokens(op, rec);
    }

    private void formatPayloadSource(DsOperation op, TableMetaData tMeta, Struct rec) {
        rec.put(DpConstants.RECORD_OFFSET_ENTITY_KEY, op.getTableName().getShortName());
        rec.put(DpConstants.SNAPSHOT_LASTONE_KEY, false);
        rec.put(DpConstants.RECORD_SOURCE_ISINCREMENT, true);
        rec.put(DpConstants.RECORD_OFFSET_TOTAL_SIZE_KEY, 0L);
        rec.put(DpConstants.RECORD_OFFSET_INDEX_KEY, 0L);
        rec.put(DpConstants.DATA_KEY_BINLOG_TS, System.currentTimeMillis());
    }

    private void formatPrimaryKeys(TableMetaData tMeta, Struct rec) {
        List<String> keys = new ArrayList();
        //The getKeyColumns method could be used.  But all it does is
        //rip the list and create a new list of just the keys
        for (ColumnMetaData cmeta : tMeta.getColumnMetaData()) {
            if (cmeta.isKeyCol()) {
                keys.add(cmeta.getOriginalColumnName());
            }
        }
        rec.put("primary_keys", keys);
    }

    private void formatTableName(TableMetaData tMeta, Struct rec) {
        rec.put("table", tMeta.getTableName().getOriginalName());
    }

    private void formatOpType(DsOperation.OpType type, Struct rec) {
        if (type.isInsert()) {
            rec.put("op_type", insertOpKey);
        } else if (type.isUpdate()) {
            rec.put("op_type", updateOpKey);
        } else if (type.isDelete()) {
            rec.put("op_type", deleteOpKey);
        }
    }

    private void formatOperationTimestamp(DsOperation op, Struct rec) {
        rec.put("op_ts", op.getTimestampAsString());
    }

    private void formatCurrentTimestamp(Struct rec) {
        rec.put("current_ts", NgUniqueTimestamp.generateUniqueTimestamp(useIso8601Format));
    }

    private void formatPosition(DsOperation op, Struct rec) {
        rec.put("pos", op.getPosition());
    }

    /**
     * Method to put token keys and values into the output data.  The generated
     * Avro schema always has a placeholder for tokens.  The field is optional
     * so Avro serialization, deserialization will not fail if the customer does
     * not configure the output to include tokens.  If the customer has
     * configured to include tokens, and there are not any, the method will not
     * add a field and simply take the Avro default which is an empty map.
     *
     * @param op  The operation.
     * @param rec The Avro record.
     */
    private void formatTokens(DsOperation op, Struct rec) {
        Map<String, String> tokenMap = new HashMap();
        if (op.getIncludeTokens()) {
            //Add the token keys and values to the map
            for (DsToken token : op.getTokens().values()) {
                tokenMap.put(token.getKey(), token.getValue());
            }
        }
        //Must have put a map here even if empty
        rec.put("tokens", tokenMap);
    }

    protected void formatColumnValue(ColumnMetaData cMeta, DsColumn col, Struct rec) {
        String fieldName = cMeta.getOriginalColumnName();
        final Object colValue;

        if (treatAllColumnsAsStrings) {
            //User has selected to treat all columns as strings
            rec.put(fieldName, col.getValue());
        } else {
            switch (cMeta.getDataType().getJDBCType()) {
                case Types.NUMERIC:
                case Types.DOUBLE:
                    BigDecimal bg = new BigDecimal(Double.valueOf(col.getValue()));
                    colValue = bg.setScale(16, BigDecimal.ROUND_HALF_UP).doubleValue();
                    break;
                case Types.BIT:
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                    colValue = Integer.parseInt(col.getValue());
                    break;
                case Types.BIGINT:
                    colValue = Long.parseLong(col.getValue());
                    break;
                case Types.FLOAT:
                case Types.REAL:
                    colValue = Float.parseFloat(col.getValue());
                    break;
                case Types.BOOLEAN:
                    colValue = Boolean.valueOf(col.getValue());
                    break;
                default:
                    colValue = col.getValue();
            }
            rec.put(fieldName, colValue);
        }
    }

    public Status metaDataChanged(DsEvent e, DsMetaData meta) {
        return Status.OK;
    }
}
