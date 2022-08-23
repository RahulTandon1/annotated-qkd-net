package com.uwaterloo.iqc.qnl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import com.uwaterloo.iqc.qnl.lsrp.LSRPRouter;
import com.uwaterloo.iqc.qnl.qll.cqptoolkit.client.GrpcClient;
import com.uwaterloo.iqc.qnl.qll.cqptoolkit.server.ISiteAgentServer;
import com.uwaterloo.iqc.qnl.qll.cqptoolkit.server.KeyTransferServer;
import com.uwaterloo.iqc.qnl.qll.cqptoolkit.server.ISiteAgentServer.ISiteAgentServerListener;
import com.cqp.remote.*;

import java.io.IOException;
import java.io.ObjectInputFilter.Config;
import java.util.Map;
import java.util.Timer;
import java.util.ArrayList;
import java.util.HashMap;

public class KeyRouter{
    private static Logger LOGGER = LoggerFactory.getLogger(KeyRouter.class);
    private static QNLConfiguration qConfig;
    private static TimerWrapper timers = new TimerWrapper();
    private static Timer timer = new Timer();

    public static void main(String[] args) throws Exception {
        if (args.length == 0)
          qConfig = new QNLConfiguration(null);
        else
          qConfig = new QNLConfiguration(args[0]);

        final KeyTransferServer server = new KeyTransferServer(qConfig);
          qConfig.createOTPKeys(server);
        server.start();

        GrpcClient client = new GrpcClient();
        //client.getSiteDetails("localhost", 8000);
        //client.startNode("localhost", 8000, "localhost", 8001);
	
        //TODO: investigate auto-generating siteagent.json, and/or find a way to communicate requirement of having such a file

        LOGGER.info("starting site agent a");
        final ISiteAgentServer siteAgent = new ISiteAgentServer(qConfig.getSiteAgentConfig().url, qConfig.getSiteAgentConfig().port);
        try {
          siteAgent.start();
        } catch(IOException e) {
          LOGGER.error("Unable to start site agent", e);
        }
        LOGGER.info("finished starting site agent a");

        siteAgent.setMySiteAgentListener(new KeyRouter());

        LinkCheck l = new LinkCheck(qConfig.getSiteAgentConfig().url, qConfig.getSiteAgentConfig().port, siteAgent, timers, false, qConfig);

        //add timers to check registry of dummy drivers here.
        timer.schedule(l, 5000, 5000); //temporary


        LOGGER.info("Key router started, args.length:" + args.length);

        LSRPRouter lsrpRouter = new LSRPRouter(qConfig);
        lsrpRouter.start();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .handler(new LoggingHandler(LogLevel.INFO))
            .childHandler(new KeyServerRouterInitializer( qConfig))
            .childOption(ChannelOption.AUTO_READ, false)
            .bind(qConfig.getConfig().getPort()).sync().channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /*@Override
    public void onDeviceRegistered(String deviceID) {
      //do not block this function
      //this function creates a timer object and thread which checks when peer dummy driver is registered on peer site agent
      //and when the above condition is met, call startNode on alice site.
      LOGGER.info("This is the deviceID: " + deviceID); // A_B_A for example
      String remoteDeviceID;

      /*if(deviceID.charAt(4) == deviceID.charAt(2)) // bob moment
      {
        remoteDeviceID = deviceID.substring(0, 4);
        remoteDeviceID += deviceID.charAt(0); // if A_B_B is calling, this is A_B_A
        LOGGER.info("The remote device ID for this site is: " + remoteDeviceID);
        if(startNodeTimers.containsKey(remoteDeviceID))
        {
          LOGGER.info("I actually made it here, but stuff went wrong from here.");
          startNodeTimers.get(remoteDeviceID).cancel();
          startNodeTimers.put(remoteDeviceID, new Timer());
          QKDLinkConfig cfg = qConfig.getQKDLinkConfig(deviceID.substring(4));
          startNodeTimers.get(remoteDeviceID).schedule(new WaitForConnect(cfg, startNodeTimers.get(remoteDeviceID)), 10000);
        }
      }
      if(deviceID.charAt(4) < deviceID.charAt(2))
      {
        remoteDeviceID = deviceID.substring(0, 4);
        remoteDeviceID += deviceID.charAt(2); // A_B_B for example
        LOGGER.info("and this is the remoteSiteID: " + remoteDeviceID);
        timers.addTimer(deviceID);
        LOGGER.info("Current number of timers: " + timers.getSize());
        QKDLinkConfig cfg = qConfig.getQKDLinkConfig(remoteDeviceID.substring(4));
        LOGGER.info("The timer being called right now is: " + deviceID);
        timers.getTimer(deviceID).schedule(new WaitForConnect(cfg, timers.getTimer(deviceID)), 0); // calling the TimerTask
      }

    }*/
}

