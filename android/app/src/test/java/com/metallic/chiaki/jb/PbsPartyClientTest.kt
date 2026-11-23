// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PbsPartyClientTest {
    @Test fun secondLanViewerUsesIndependentControllerAndKeepsFirstOwner() = runBlocking {
        val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val workers=Executors.newCachedThreadPool()
        val primaryConnected=AtomicInteger(0)
        val guestConnected=AtomicInteger(0)
        val primaryToken="11111111111111111111111111111111"
        val guestToken="22222222222222222222222222222222"
        val acceptor=Thread {
            while(!listener.isClosed) {
                try {
                    val socket=listener.accept()
                    workers.execute {
                        socket.use {peer ->
                            peer.soTimeout=3000
                            val reader=BufferedReader(InputStreamReader(peer.getInputStream(),Charsets.US_ASCII))
                            val request=reader.readLine() ?: return@use
                            val requestPath=request.split(' ').getOrNull(1) ?: return@use
                            val path=requestPath.substringBefore('?')
                            val headers=mutableMapOf<String,String>()
                            while(true) {
                                val line=reader.readLine() ?: break
                                if(line.isEmpty()) break
                                val index=line.indexOf(':')
                                if(index>=0) headers[line.substring(0,index).lowercase()]=line.substring(index+1).trim()
                            }
                            val count=headers["content-length"]?.toIntOrNull() ?: 0
                            repeat(count.coerceIn(0,256)) {reader.read()}
                            var code=200
                            val response=when(path) {
                                "/input/connect" -> {
                                    if(primaryConnected.compareAndSet(0,1))
                                        "{\"token\":\""+primaryToken+"\"}"
                                    else {code=409;"{\"message\":\"primary busy\"}"}
                                }
                                "/party/status" -> "{\"version\":1,\"max_players\":4,\"connected\":2,\"players\":["+
                                    "{\"player\":1,\"connected\":true,\"cleanup_required\":false,\"prompt_sent\":true},"+
                                    "{\"player\":2,\"connected\":true,\"cleanup_required\":false,\"prompt_sent\":false},"+
                                    "{\"player\":3,\"connected\":false,\"cleanup_required\":false,\"prompt_sent\":false},"+
                                    "{\"player\":4,\"connected\":false,\"cleanup_required\":false,\"prompt_sent\":false}]}"
                                "/party/connect" -> {
                                    assertEquals("/party/connect",requestPath)
                                    if(primaryConnected.compareAndSet(0,1))
                                        "{\"token\":\""+primaryToken+"\",\"player\":1}"
                                    else {
                                        guestConnected.incrementAndGet()
                                        "{\"token\":\""+guestToken+"\",\"player\":2}"
                                    }
                                }
                                "/party/disconnect" -> {
                                    assertEquals(guestToken,headers["x-pbs-controller"])
                                    guestConnected.decrementAndGet()
                                    "{}"
                                }
                                "/input/disconnect" -> {
                                    assertEquals(primaryToken,headers["x-pbs-controller"])
                                    primaryConnected.decrementAndGet()
                                    "{}"
                                }
                                "/input/state" -> {
                                    assertEquals(primaryToken,headers["x-pbs-controller"])
                                    "{}"
                                }
                                "/party/state" -> {
                                    assertEquals(guestToken,headers["x-pbs-controller"])
                                    "{}"
                                }
                                else -> {code=404;"{\"message\":\"not found\"}"}
                            }
                            val bytes=response.toByteArray(Charsets.UTF_8)
                            val output=peer.getOutputStream()
                            val header="HTTP/1.1 "+code+" OK\r\nContent-Type: application/json\r\nContent-Length: "+bytes.size+"\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray(Charsets.US_ASCII))
                            output.write(bytes);output.flush()
                        }
                    }
                } catch(_:java.net.SocketException) {break}
            }
        }.apply {isDaemon=true;start()}
        val first=PbsClient("127.0.0.1",listener.localPort)
        val second=PbsClient("127.0.0.1",listener.localPort)
        try {
            assertEquals(primaryToken,first.openController())
            assertEquals(1,first.playerSlot)
            assertEquals(guestToken,second.openController())
            assertEquals(2,second.playerSlot)
            assertEquals(2,second.partyStatus()!!.connectedCount)
            second.sendController(guestToken,1,PbsProtocol.PadState())
            first.sendController(primaryToken,1,PbsProtocol.PadState())
            second.releaseController()
            assertEquals(0,second.playerSlot)
            assertEquals(1,primaryConnected.get())
            assertEquals(0,guestConnected.get())
            first.releaseController()
            assertEquals(0,primaryConnected.get())
        } finally {
            first.close();second.close();listener.close()
            workers.shutdownNow();acceptor.join(1000)
        }
    }
}
