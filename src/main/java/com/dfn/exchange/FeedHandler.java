package com.dfn.exchange;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.dfn.exchange.beans.*;
import com.dfn.exchange.registry.StateRegistry;
import com.google.gson.Gson;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import quickfix.FieldNotFound;
import quickfix.field.Price;
import quickfix.fix42.NewOrderSingle;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by darshanas on 11/2/2017.
 */
public class FeedHandler extends UntypedActor {

    private static final Logger logger = LogManager.getLogger(FeedHandler.class);

    private final int port = 16500;
    private ServerSocket serverSocket = null;
    private List<SocketHandler> socketList = new ArrayList<>();
    private Map<Integer,SocketHandler> sockMap = new HashMap<>();
    private ActorRef socketHandlerActor;
    private ActorRef webSocketHandlerActor;
    Gson gson = new Gson();
    private final String exchangePath = "akka://stockExchange/user/ExchangeSupervisor";
    private ActorRef exchangeActor = null;

    @Override
    public void preStart() throws Exception {
        super.preStart();
        exchangeActor = getContext().actorFor(exchangePath);
        System.out.println("Starting feed handler");
        System.out.println("Feed Hander Path " + getSelf().path());
        Runnable r = () -> startServerSocket();
        new Thread(r).start();
        logger.info("Starting feed handler");
        logger.info("Feed Hander Path " + getSelf().path());
        socketHandlerActor = getContext().actorOf(Props.create(SocketFeedHandler.class));
        webSocketHandlerActor = getContext().actorOf(Props.create(WebSocketFeedHandler.class));
    }

    @Override
    public void onReceive(Object message) throws Exception {

        socketHandlerActor.tell(message,getSelf());
        webSocketHandlerActor.tell(message,getSelf());

        if(message instanceof TradeMatch){
            StateRegistry.matchCount++;
        }else if(message instanceof MarketVolume){
            MarketVolume vol = (MarketVolume)message;
            StateRegistry.putMarketVolumeState(vol.getSymbol(),vol);
        }else if(message instanceof OrderBook){
            OrderBook ob = (OrderBook) message;
            StateRegistry.addOrderBookSnapShot(ob.getSymbol(),ob);
        }

    }


    private void sendMessage(String message){
        if(message != null && !message.equals("")){
            write(message);
        }
    }


    private Quote getQuote(NewOrderSingle order) throws FieldNotFound{
        Quote quote = new Quote();
        if(order.isSet(new Price()))
            quote.setPrice(order.getPrice().getValue());
        quote.setQty(order.getOrderQty().getValue());
        quote.setSide(order.getSide().getValue());
        quote.setSymbol(order.getSymbol().getValue());
        quote.setTif(order.getTimeInForce().getValue());
        quote.setType(order.getOrdType().getValue());
        quote.setMessageType('D');
        return quote;
    }

    private void startServerSocket(){

        try {
            serverSocket = new ServerSocket(port);
//            int socktId = 1;
            while (true) {

                logger.info("Listening to connections");
                final Socket activeSocket = serverSocket.accept();
                SocketHandler h = new SocketHandler(activeSocket);
                socketList.add(h);
//                sockMap.put(socktId, h);
//                socktId++;

            }


        } catch (IOException e) {
            e.printStackTrace();
        }


    }


    private void write(String message){
        socketList.forEach(s -> {
            s.write(message);
        });

    }


    class SocketHandler{

        private Socket socket;
        BufferedReader socketReader = null;
        BufferedWriter socketWriter = null;
        Thread readerThred;
        private boolean isActive = true;


        public SocketHandler(Socket socket){

            try {
                this.socket = socket;
                socketReader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream()));
                socketWriter = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream()));
                Runnable r = () -> startReadSocket();
                readerThred = new Thread(r);
                //sending initial symbol details response.
                write(Settings.getSymbolString());
                Timeout timeout = new Timeout(Duration.create(2, "seconds"));
                Future<Object> future = Patterns.ask(exchangeActor, new StatusReq(), timeout);
                try {
                    StatusMessage statusMessage = (StatusMessage) Await.result(future, timeout.duration());
                    if(statusMessage != null)
                        write(gson.toJson(statusMessage));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        public void write(String message)  {

            try {
                if(isActive){
                    socketWriter.write(message);
                    socketWriter.write("\n");
                    socketWriter.flush();
                }
            } catch (IOException e) {
                System.out.println("Removing client from store");
                isActive = false;
            }
        }

        private void startReadSocket(){

            String inMsg = null;

            try {
                while ((inMsg = socketReader.readLine()) != null) {

                    System.out.println("New in message from price " + inMsg);

                }
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    }


//        socketHandlerActor.tell(message, getSelf());
//        webSocketHandlerActor.tell(message, getSelf());





}
