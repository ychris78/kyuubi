/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.ui

import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.eclipse.jetty.server.{HttpConfiguration, HttpConnectionFactory, Request, Server, ServerConnector}
import org.eclipse.jetty.server.handler.{ContextHandlerCollection, ErrorHandler, HandlerWrapper}
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.util.component.LifeCycle
import org.eclipse.jetty.util.thread.{QueuedThreadPool, ScheduledExecutorScheduler}

import org.apache.kyuubi.Utils.isWindows
import org.apache.kyuubi.server.http.authentication.AuthenticationFilter

private[kyuubi] case class JettyServer(
    server: Server,
    connector: ServerConnector,
    rootHandler: ContextHandlerCollection) {

  def start(): Unit = synchronized {
    try {
      server.start()
      connector.start()
      server.addConnector(connector)
    } catch {
      case e: Exception =>
        stop()
        throw e
    }
  }

  def stop(): Unit = synchronized {
    server.stop()
    connector.stop()
    server.getThreadPool match {
      case lifeCycle: LifeCycle => lifeCycle.stop()
      case _ =>
    }
  }
  def getServerUri: String = connector.getHost + ":" + connector.getLocalPort

  def addHandler(handler: ServletContextHandler): Unit = synchronized {
    val handlerWrapper = new HandlerWrapper {
      override def handle(
          target: String,
          baseRequest: Request,
          request: HttpServletRequest,
          response: HttpServletResponse): Unit = {
        try {
          super.handle(target, baseRequest, request, response)
        } finally {
          AuthenticationFilter.HTTP_CLIENT_USER_NAME.remove()
          AuthenticationFilter.HTTP_CLIENT_IP_ADDRESS.remove()
        }
      }
    }
    handlerWrapper.setHandler(handler)
    rootHandler.addHandler(handlerWrapper)
    if (!handler.isStarted) handler.start()
    if (!handlerWrapper.isStarted) handlerWrapper.start()
  }

  def addStaticHandler(
      resourceBase: String,
      contextPath: String): Unit = {
    addHandler(JettyUtils.createStaticHandler(resourceBase, contextPath))
  }

  def addRedirectHandler(
      src: String,
      dest: String): Unit = {
    addHandler(JettyUtils.createRedirectHandler(src, dest))
  }
}

object JettyServer {

  def apply(name: String, host: String, port: Int): JettyServer = {
    // TODO: Configurable pool size
    val pool = new QueuedThreadPool()
    pool.setName(name)
    pool.setDaemon(true)
    val server = new Server(pool)

    val errorHandler = new ErrorHandler()
    errorHandler.setShowStacks(true)
    errorHandler.setServer(server)
    server.addBean(errorHandler)

    val collection = new ContextHandlerCollection
    server.setHandler(collection)

    val serverExecutor = new ScheduledExecutorScheduler(s"$name-JettyScheduler", true)
    val httpConf = new HttpConfiguration()
    val connector = new ServerConnector(
      server,
      null,
      serverExecutor,
      null,
      -1,
      -1,
      new HttpConnectionFactory(httpConf))
    connector.setHost(host)
    connector.setPort(port)
    connector.setReuseAddress(!isWindows)
    connector.setAcceptQueueSize(math.min(connector.getAcceptors, 8))

    new JettyServer(server, connector, collection)
  }
}