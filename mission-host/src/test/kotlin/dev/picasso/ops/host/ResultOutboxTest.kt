package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.store.*
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.concurrent.Executors
import kotlin.test.*

class ResultOutboxTest {
 private val ds=DriverManagerDataSource(PostgresSupport.jdbcUrl,PostgresSupport.username,PostgresSupport.password)
 private val jdbc=JdbcClient.create(ds)
 private val guard=TestLease.guard(ds)
 private val records=HostRecords(jdbc,guard,ObjectMapper())
 private val snapshot=ObjectMapper().readTree("{\"jobOrderId\":\"JO-1\"}")
 @BeforeTest fun fresh(){Fenced.execute("DROP SCHEMA IF EXISTS mission CASCADE");HostSchema.flyway(ds).migrate()}
 @Test fun `rollback leaves no event or cursor gap and replay stays unique`() {
  assertFailsWith<IllegalStateException>{records.inTransaction{records.appendResult("mw","r1",snapshot);error("rollback")}}
  assertEquals(0L,records.resultEvents(0,10)["highWatermark"])
  records.inTransaction{records.appendResult("mw","r1",snapshot)}
  records.inTransaction{records.appendResult("mw","r1",snapshot)}
  assertEquals(1L,records.resultEvents(0,10)["highWatermark"])
  val reconstructed=HostRecords(jdbc,guard,ObjectMapper())
  assertEquals(records.resultEvents(0,10),reconstructed.resultEvents(0,10))
  assertFailsWith<IllegalArgumentException>{records.resultEvents(2,10)}
  assertFailsWith<org.springframework.dao.DataAccessException>{jdbc.sql("DELETE FROM mission.result_outbox").update()}
 }
 @Test fun `concurrent writers allocate committed contiguous pages`() {
  Executors.newFixedThreadPool(4).use{pool->
   (1..12).map{id->pool.submit{records.inTransaction{records.appendResult("mw","r$id",snapshot)}}}.forEach{it.get()}
  }
  @Suppress("UNCHECKED_CAST") val page=records.resultEvents(0,5)["events"] as List<Map<String,Any>>
  assertEquals((1L..5L).toList(),page.map{it["sequence"]})
  @Suppress("UNCHECKED_CAST") val tail=records.resultEvents(5,20)["events"] as List<Map<String,Any>>
  assertEquals((6L..12L).toList(),tail.map{it["sequence"]})
 }
}
