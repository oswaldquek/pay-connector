package uk.gov.pay.connector.util;

import org.apache.commons.lang3.StringUtils;
import org.skife.jdbi.v2.DefaultMapper;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class ChargesSearch {

    public static List<Map<String, Object>> createQueryHandle(Handle handle, String query, String gatewayAccountId,
                                                       String reference, String status, String fromDate, String toDate) {

        Query<Map<String, Object>> queryStmt = handle
                .createQuery(String.format(query, constructSearchTransactionsQuery(reference, status, fromDate, toDate)))
                .bind("gid", Long.valueOf(gatewayAccountId));

        // Filter by reference or chargeId
        if (StringUtils.isNotBlank(reference)) {
            queryStmt.bind("reference", "%" + reference + "%");
            if (StringUtils.isNumeric(reference)) {
                queryStmt.bind("charge_id", "%" + reference + "%");
            }
        }

        // Filter by status
        if (StringUtils.isNotBlank(status)) {
            queryStmt.bind("status", status);
        }

        // Filter by Date(s)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        if (StringUtils.isNotBlank(fromDate)) {
            queryStmt.bind("fromDate", Timestamp.valueOf(LocalDateTime.parse(fromDate, formatter)));
        }
        if (StringUtils.isNotBlank(toDate)) {
            queryStmt.bind("toDate", Timestamp.valueOf(LocalDateTime.parse(toDate, formatter)));
        }
        return queryStmt.map(new DefaultMapper()).list();
    }

    private static String constructSearchTransactionsQuery(String reference, String status, String fromDate, String toDate) {
        StringBuffer subQuery = new StringBuffer();
        String AND = " AND ";

        // Filter by reference or chargeId
        if (isNotBlank(reference)) {
            subQuery.append(AND);
            if( StringUtils.isNumeric(reference)) {
                subQuery.append("(c.reference LIKE :reference OR CAST(c.charge_id AS TEXT) LIKE :reference)");
            } else {
                subQuery.append("c.reference LIKE :reference");
            }
        }

        // Filter by status
        if (isNotBlank(status)) {
            subQuery.append(AND);
            subQuery.append("c.status=:status");
        }

        // Filter by Date(s)
        if (isNotBlank(fromDate)) {
            subQuery.append(AND);
            subQuery.append("ce.updated >= :fromDate");
        }
        if (isNotBlank(toDate)) {
            subQuery.append(AND);
            subQuery.append("ce.updated <= :toDate");
        }

        return subQuery.toString();
    }
}