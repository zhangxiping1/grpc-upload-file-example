package cn.edu.sjtu.ops.grpcdemo;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.UUID;
import java.util.logging.*;

import org.apache.hadoop.fs.FSDataOutputStream;

public class DemoServer {

    private final int port;
    private final Server server;
    private static Logger logger;

    private static final int CHUNKSIZE = 1;

    public DemoServer(int port) throws IOException {
        logger = LoggerFactory.getLogger(DemoServer.class);
        this.port = port;
        ServerBuilder sb = ServerBuilder.forPort(port);
        this.server = sb.addService(new DemoService()).build();
    }

    /**
     * Start serving requests.
     */
    public void start() throws IOException {
        logger.info("************ START *************");

        server.start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may has been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                DemoServer.this.stop();
                System.err.println("*** server shut down");
            }
        });
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
        logger.info("************ FINISH ************");
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main method.  This comment makes the linter happy.
     */
    public static void main(String[] args) throws Exception {


        DemoServer server = new DemoServer(8980);
        server.start();
        server.blockUntilShutdown();

    }

    public static class DemoService extends DemoServiceGrpc.DemoServiceImplBase {
        @Override
        public StreamObserver<Chunk> upload(final StreamObserver<UploadStatus> responseObserver) {
            return new StreamObserver<Chunk>() {
//                ByteArrayOutputStream bos = new ByteArrayOutputStream(CHUNKSIZE);
                String filename = UUID.randomUUID().toString();
                int count = 0;
                FSDataOutputStream output;
                Hdfs hdfs = null;
                boolean first = true;
                public void onNext(Chunk chunk) {
                    try {
                        if (first == true) {
                            first = false;
                            hdfs = Hdfs.getInstance();
                            hdfs.mkdir("/" + Thread.currentThread().getName());
                            String path = "/" + Thread.currentThread().getName() + "/" + filename;
                            output = hdfs.create(path, true);

                        } else {
                            output.write(chunk.getContent().toByteArray());
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    count++;
                    logger.info("chunk-" + String.valueOf(count) + " start");
                }


                public void onError(Throwable throwable) {
                    logger.info("error!!!!");
                }

                public void onCompleted() {
                    try {
                        if (output != null) {
                            try {
                                output.close();
                            } catch (Exception e) {
                                logger.error(String.valueOf(e));
                            }
                        }
                        if (hdfs != null) {
                            try {
                                hdfs.close();
                            } catch (IOException e) {
                                logger.error(String.valueOf(e));
                            }
                        }
                    } catch (RuntimeException e) {
                        throw new RuntimeException(e);
                    }
                    logger.info("complete!!!!!");
                    logger.info("File Transfer Completed\n");
                    responseObserver.onNext(UploadStatus.newBuilder().setCodeValue(1).build());
                    responseObserver.onCompleted();
                }
            };
        }
    }

    static class  MyLogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getMillis() +  ": " + record.getMessage() + "\n";
        }
    }
}
