package uk.gov.pay.connector.dao;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.util.StringMapper;

public class GatewayAccountDao {
    private DBI jdbi;

    public GatewayAccountDao(DBI jdbi) {
        this.jdbi = jdbi;
    }

    public String insertNameAndReturnNewId(String name) {
        return jdbi.withHandle(handle ->
                        handle
                                .createStatement("INSERT INTO gateway_accounts(name) VALUES (:name)")
                                .bind("name", name)
                                .executeAndReturnGeneratedKeys(StringMapper.FIRST)
                                .first()
        );
    }
}
