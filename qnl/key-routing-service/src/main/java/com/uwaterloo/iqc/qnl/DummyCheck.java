package com.uwaterloo.iqc.qnl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.uwaterloo.iqc.qnl.qll.cqptoolkit.client.GrpcClient;
import com.cqp.remote.*;

import java.util.Timer;
import java.util.TimerTask;

public class DummyCheck extends TimerTask{
    private static Logger LOGGER = LoggerFactory.getLogger(KeyRouter.class);
    private QKDLinkConfig cfg;

    DummyCheck(QKDLinkConfig cfg)
    {
        this.cfg = cfg;
    }

    public void run()
    {
        GrpcClient client = new GrpcClient();
        String localAddress = cfg.localSiteAgentUrl.split(":")[0];
        int localPort = Integer.parseInt(cfg.localSiteAgentUrl.split(":")[1]);
        String remoteAddress = cfg.remoteSiteAgentUrl.split(":")[0];
        int remotePort = Integer.parseInt(cfg.remoteSiteAgentUrl.split(":")[1]);
        Site localSite = client.getSiteDetails(localAddress, localPort);
        Site remoteSite = client.getSiteDetails(remoteAddress, remotePort);
        if(localSite.getDevicesCount() == 0)
            LOGGER.info("local dummy driver isn't there on the local site agent right now. The local device ID is: " + cfg.localQKDDeviceId);
        if(remoteSite.getDevicesCount() == 0)
            LOGGER.info("remote dummy driver isn't there on the remote site agent right now. The remote device ID is: " + cfg.remoteQKDDeviceId);
    }
}