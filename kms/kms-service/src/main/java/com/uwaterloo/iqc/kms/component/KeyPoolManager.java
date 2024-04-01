package com.uwaterloo.iqc.kms.component;

import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import com.uwaterloo.iqc.kms.controller.KMSController;

@Configuration
@PropertySource(value = "file:${SITE_PROPERTIES}", ignoreResourceNotFound = true)
public class KeyPoolManager {

    class PoolLock {
        boolean inProgress;
        Lock lock;
    }
    private static final Logger logger = LoggerFactory.getLogger(KeyPoolManager.class);
    private ConcurrentMap<String, KeyPool> keyPools = new ConcurrentHashMap<>();
    private ConcurrentMap<String, PoolLock> poolLocks = new ConcurrentHashMap<>();
    private ReentrantLock initPoolLock=  new ReentrantLock();
    @Value("${kms.keys.bytesize}") private int byteSz;
    @Value("${kms.keys.blocksize}") private long blockSz;
    @Value("${kms.site.id}") private String localSiteId;
    @Value("${kms.keys.dir}") private String poolsDir;
    @Value("${qnl.ip}") private String qnlIP;
    @Value("${qnl.port}") private int qnlPort;
    @Autowired	private QNLKeyReader keyReader;

    @Bean
    public KeyPoolManager keyPoolMgr() {
        KeyPoolManager kp = new  KeyPoolManager();
        return  kp;
    }

    public int getKeyByteSize() {
        return byteSz;
    }

    public long getKeyBlockSize() {
        return blockSz;
    }

    public String getLocalSiteId() {
        return localSiteId;
    }

    public Key newKey(String siteId) {
        Key key = null;
        if (siteId == null)
            return null;
        try {
            key = fetchKey(siteId, null, -1L);
        } catch(Exception e) {
        }
        if (key == null)
            key = new Key();
        return key;
    }


    public Key getKey(String siteId, String block, long index) {
        Key key;
        if (siteId == null || block == null || index < 0)
            return null;
        try {
            key = fetchKey(siteId, block, index);
        } catch(Exception e) {
            key = new Key();
        }
        return key;
    }

    private Key fetchKey(String siteId, String inBlockId, long ind) throws Exception {
        String blockId = inBlockId;
        Key cipherKey = null;
        String srcSiteId;
        String dstSiteId;
        PoolLock poolLock;
        String poolName;
        int index = (int)ind; // -1
        
        logger.info("[rahul debug]: fetchKey siteId= " + siteId + " inBlockID = " + inBlockId + " index " + index);
        
        if (index < 0) {
            logger.info("[rahul debug]: fetchKey: (index < 0)");
            srcSiteId = localSiteId;
            dstSiteId = siteId;
        } else {
            logger.info("[rahul debug]: fetchKey: (index >= 0)");
            srcSiteId = siteId;
            dstSiteId = localSiteId;
        }

        poolName = srcSiteId + dstSiteId;

        logger.info("KeyPoolManager.fetchKey:" + srcSiteId + "->" + dstSiteId + ",index=" + ind); // B->A,index=-1
        if (containsPool(poolName)) {
            cipherKey = key(poolName, index);
        } else if (containsPoolLock(poolName) &&
                   keyPoolLock(poolName).inProgress) {
            logger.info("KeyPoolManager.fetchKey is progressing, please wait ...");
            poolLock = keyPoolLock(poolName);
            synchronized(poolLock.lock) {
                try {
                    while (poolLock.inProgress) {
                        poolLock.lock.wait();
                    }
                } catch(Exception e) {
                	logger.error("Intention was to be notified ", e);
                } finally {}
            }
            cipherKey = key(poolName, index);
        } else {
            logger.info("KeyPoolManager.fetchKey starts ...");
            initPoolLock.lock();  // block until condition holds
            try {
                if (containsPool(poolName)) {
                    cipherKey = key(poolName, index);
                    initPoolLock.unlock();
                } else if (containsPoolLock(poolName) &&
                           keyPoolLock(poolName).inProgress) {
                    poolLock = keyPoolLock(poolName);
                    synchronized(poolLock.lock) {
                        initPoolLock.unlock();
                        try {
                            while (poolLock.inProgress) {
                                poolLock.lock.wait();
                            }
                        } catch(Exception e) {
                        	logger.error("Intention was to be notified while waiting for the key block ", e);
                        } finally {}
                    }
                    cipherKey = key(poolName, index);
                } else {
                    logger.info("[rahul debug]: [fetchKey] inside 'fetching work' case.");
                    if (containsPoolLock(poolName)) {
                        poolLock = keyPoolLock(poolName);
                        poolLock.inProgress = true;
                        initPoolLock.unlock();
                    } else {
                        poolLock = new PoolLock();
                        poolLock.inProgress = true;
                        poolLock.lock = new ReentrantLock();
                        poolLocks.put(poolName, poolLock);
                        initPoolLock.unlock();
                    }
                    //do the fetching work
                    Vector<String> keyBlockDst = new Vector<>((int)blockSz);
                    try {
                        // perform read from local QNL layer at qnl.ip, qnl.port
                        // keyBlockDst will be edited after this call (pass by reference behaviour)
                        if (blockId == null && index == -1L)
                            blockId = keyReader.read(localSiteId,
                                                     siteId, keyBlockDst,
                                                     qnlIP, qnlPort, poolsDir, (int)blockSz, byteSz);
                        else
                            keyReader.read(siteId, localSiteId,
                                           blockId, keyBlockDst, poolsDir, blockSz);
                    } catch(Exception e) {
                        logger.error("Problem occurred while fetching key block ", e);
                        throw e;
                    }

                    KeyPool removed = keyPools.remove(poolName);
                    if (removed != null) {
                        String fileName = this.poolsDir + File.separator + srcSiteId + File.separator + dstSiteId + File.separator + removed.getBlockId();
                        logger.info("Delete keypool " + fileName);
                        File file = new File(fileName);
                        if (file != null) {
                            file.delete();
                        }
                    }
                    KeyPool kp = new KeyPool(blockId, blockSz, keyBlockDst);
                    logger.info("KeyPoolManager.fetchKey, add keypool: " + poolName + ",blockId:" + blockId + ",blockSz:" + blockSz + ",keyBlockDest:" + keyBlockDst);
                    keyPools.put(poolName, kp);

                    synchronized(poolLock.lock) {
                        poolLock.inProgress = false;
                        poolLock.lock.notifyAll();
                    }
                    cipherKey = key(poolName, index);
                }
            } catch(Exception e) {
            	logger.error("Problem occurred while fetching key block ", e);
            }	 finally {
            }
        }
        return cipherKey;
    }

    private boolean containsPool(String poolName)  {
        boolean isPool = keyPools.containsKey(poolName);
        boolean validKeys = false;
        KeyPool kp;

        if (isPool) {
            kp = keyPools.get(poolName);
            validKeys = kp.isValid();
        }
        return (isPool && validKeys);
    }

    private Key key(String poolName, int index) {
        if (index < 0)
            return keyPools.get(poolName).getKey();
        else
            return keyPools.get(poolName).getKey(index);
    }

    private boolean containsPoolLock(String poolName) {
        return poolLocks.containsKey(poolName);
    }


    private PoolLock keyPoolLock(String poolName) {
        return poolLocks.get(poolName);
    }


    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyConfigInDev() {
        return new PropertySourcesPlaceholderConfigurer();
    }
}
