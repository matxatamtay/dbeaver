package org.jkiss.dbeaver.teststudio.spi;

import com.google.gson.JsonObject;

public interface StudioSession extends AutoCloseable {
   JsonObject connection();
   String productName();
   String driverId();
   boolean supportsTransactions();
   boolean transactionActive() throws Exception;
   JsonObject execute(String sql, int maxRows, int timeoutSeconds) throws Exception;
   void commit() throws Exception;
   void rollback() throws Exception;
   @Override void close() throws Exception;
}
