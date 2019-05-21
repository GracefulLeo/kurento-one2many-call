/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.one2manycall;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to N video call communication.
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
@SuppressWarnings("Duplicates")
public class CallHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CallHandler.class);
    private static final Gson gson = new GsonBuilder().create();

    private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();

    @Autowired
    private KurentoClient kurento;


    private List<UserSession> presenterSessionList = new ArrayList<>();
    private List<UserSession> subscriberSessionList = new ArrayList<>();
    private MediaPipeline pipeline;
    private UserSession presenterUserSession;
    private Composite composite;
    private List<HubPort> hubPortList = new ArrayList<>();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
        log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

        switch (jsonMessage.get("id").getAsString()) {
            case "presenter":
                try {
                    presenter(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "presenterResponse");
                }
                break;
            case "viewer":
                try {
                    viewer(session, jsonMessage);
                } catch (Throwable t) {
                    handleErrorResponse(t, session, "viewerResponse");
                }
                break;
            case "onIceCandidate": {
                JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

                UserSession user = null;
                if (presenterUserSession != null) {
                    if (presenterUserSession.getSession() == session) {
                        user = presenterUserSession;
                    } else {
                        user = viewers.get(session.getId());
                    }
                }
                if (user != null) {
                    IceCandidate cand =
                            new IceCandidate(candidate.get("candidate").getAsString(), candidate.get("sdpMid")
                                    .getAsString(), candidate.get("sdpMLineIndex").getAsInt());
                    user.addCandidate(cand);
                }
                break;
            }
            case "stop":
                stop(session);
                break;
            default:
                break;
        }
    }

    private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
            throws IOException {
        stop(session);
        log.error(throwable.getMessage(), throwable);
        JsonObject response = new JsonObject();
        response.addProperty("id", responseId);
        response.addProperty("response", "rejected");
        response.addProperty("message", throwable.getMessage());
        session.sendMessage(new TextMessage(response.toString()));
    }

    private synchronized void presenter(final WebSocketSession session, JsonObject jsonMessage)
            throws IOException {
        System.out.println(presenterSessionList.size());
        if(presenterSessionList.size()>3 || (hubPortList.size() != 0 && hubPortList.size() < presenterSessionList.size())){
            JsonObject response = new JsonObject();
            response.addProperty("id", "presenterResponse");
            response.addProperty("response", "rejected");
            response.addProperty("message",
                    "Maximum amount of publishers is reached. Try again later ...");
            session.sendMessage(new TextMessage(response.toString()));
        } else {
            presenterUserSession = new UserSession(session);
            if (pipeline == null) {
                pipeline = kurento.createMediaPipeline();
            }
            presenterUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).build());

            WebRtcEndpoint presenterWebRtc = presenterUserSession.getWebRtcEndpoint();

            presenterWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("id", "iceCandidate");
                    response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(response.toString()));
                        }
                    } catch (IOException e) {
                        log.debug(e.getMessage());
                    }
                }
            });


            presenterUserSession.setId(presenterSessionList.size());
            presenterSessionList.add(presenterUserSession);
            if (composite == null) {
                composite = new Composite.Builder(pipeline).build();
            }

            HubPort hubPort = new HubPort.Builder(composite).build();
            hubPortList.add(hubPort);

            presenterWebRtc.connect(hubPortList.get(presenterSessionList.size()-1));

            String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
            String sdpAnswer = presenterWebRtc.processOffer(sdpOffer);

            JsonObject response = new JsonObject();
            response.addProperty("id", "presenterResponse");
            response.addProperty("response", "accepted");
            response.addProperty("sdpAnswer", sdpAnswer);

            synchronized (session) {
                presenterUserSession.sendMessage(response);
            }
            presenterWebRtc.gatherCandidates();
        }
    }

    private synchronized void viewer(final WebSocketSession session, JsonObject jsonMessage)
            throws IOException {

        if (subscriberSessionList.size() > 4){
            JsonObject response = new JsonObject();
            response.addProperty("id", "presenterResponse");
            response.addProperty("response", "rejected");
            response.addProperty("message",
                    "Maximum amount of subscribers is reached. Try again later ...");
            session.sendMessage(new TextMessage(response.toString()));
        } else{

        if (presenterUserSession == null || presenterUserSession.getWebRtcEndpoint() == null) {
            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerResponse");
            response.addProperty("response", "rejected");
            response.addProperty("message",
                    "No active sender now. Become sender or . Try again later ...");
            session.sendMessage(new TextMessage(response.toString()));
        } else {
            UserSession viewer = new UserSession(session);

            viewers.put(session.getId(), viewer);

            WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

            nextWebRtc.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

                @Override
                public void onEvent(IceCandidateFoundEvent event) {
                    JsonObject response = new JsonObject();
                    response.addProperty("id", "iceCandidate");
                    response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
                    try {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(response.toString()));
                        }
                    } catch (IOException e) {
                        log.debug(e.getMessage());
                    }
                }
            });

            for (UserSession userSession : presenterSessionList) {
                viewer.setWebRtcEndpoint(nextWebRtc);
                userSession.getWebRtcEndpoint().connect(nextWebRtc);
            }
            HubPort hubPort = new HubPort.Builder(composite).build();
            hubPort.connect(nextWebRtc);
            nextWebRtc.connect(hubPort);


            String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
            String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

            JsonObject response = new JsonObject();
            response.addProperty("id", "viewerResponse");
            response.addProperty("response", "accepted");
            response.addProperty("sdpAnswer", sdpAnswer);

            synchronized (session) {
                viewer.sendMessage(response);
            }
            nextWebRtc.gatherCandidates();
            subscriberSessionList.add(viewer);
        }
        }
    }

    private synchronized void stop(WebSocketSession session) throws IOException {
        String sessionId = session.getId();
        if (presenterSessionList.size() != 0) {
            for (UserSession userSession : presenterSessionList) {
                if (userSession.getSession().getId().equals(sessionId)) {

                    log.info("Releasing media pipeline");
//                    if (pipeline != null) {
//                        pipeline.release();
//                    }
//                    pipeline = null;
                    if (viewers.containsKey(sessionId)) {
                        if (viewers.get(sessionId).getWebRtcEndpoint() != null) {
                            viewers.get(sessionId).getWebRtcEndpoint().release();

                        }
                        viewers.remove(sessionId);
                    }
                    hubPortList.get(userSession.getId()).release();
                    hubPortList.remove(userSession.getId());
                    userSession.getWebRtcEndpoint().release();
                    presenterSessionList.remove(userSession);
                    userSession = null;
                    break;
                }

            }
//            && presenterSessionList.getSession().getId().equals(sessionId)) {
        }
        if (presenterSessionList.size() == 0) {
            for (UserSession viewer : viewers.values()) {
                JsonObject response = new JsonObject();
                response.addProperty("id", "stopCommunication");
                viewer.sendMessage(response);
            }
            pipeline.release();
            pipeline = null;
            composite = null;
        }

    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        stop(session);
    }

}
