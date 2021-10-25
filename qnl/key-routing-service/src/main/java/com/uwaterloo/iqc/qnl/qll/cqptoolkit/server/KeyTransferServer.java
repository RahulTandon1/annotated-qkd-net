package com.uwaterloo.iqc.qnl.qll.cqptoolkit.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;

import com.cqp.remote.KeyTransferGrpc.KeyTransferImplBase;
import com.cqp.remote.*;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.binary.Hex;

public class KeyTransferServer {
    private static Logger LOGGER = LoggerFactory.getLogger(KeyTransferServer.class);

    private final int port;
    private final Server server;

    public KeyTransferServer() throws IOException {
        this.port = 50051;
        this.server = ServerBuilder.forPort(this.port).addService(new KeyTransferService()).build();
    }

    public void start() throws IOException {
        this.server.start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    KeyTransferServer.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
            }
        });
        
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static class KeyTransferService extends KeyTransferGrpc.KeyTransferImplBase {
        KeyTransferService() {
        }
        @Override
        public void sendKey(Key keyMessage, StreamObserver<Empty> responseObserver) {
            
            try {
                FileWriter fw = new FileWriter("KeyTransferLog.txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw);
                out.println("Key " + keyMessage.getSeqID() + " received: " + Hex.encodeHexString(keyMessage.getKey().toByteArray()));
                out.close();
                bw.close();
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Empty reply = Empty.newBuilder().build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
       
}