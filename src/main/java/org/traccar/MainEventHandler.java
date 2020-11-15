/*
 * Copyright 2012 - 2019 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.StatisticsManager;
import org.traccar.helper.DateUtil;
import org.traccar.model.Position;

import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainEventHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainEventHandler.class);

    private static final String DEFAULT_LOGGER_ATTRIBUTES = "time,position,speed,course,accuracy,result";

    private final Set<String> connectionlessProtocols = new HashSet<>();
    private final Set<String> logAttributes = new LinkedHashSet<>();

    private static final Double earthRad = 6378137.0;

    //4326 to 3875
    public Double standardLat(Double lat) {
        Double a = lat * Math.PI / 180;
        Double result = earthRad / 2 * Math.log((1.0 + Math.sin(a)) / (1.0 - Math.sin(a)));
        return result;
    }

    //4326 to 3875
    public Double standardLon(Double lon) {
        Double result = lon * Math.PI / 180 * earthRad;
        return result;
    }

    public MainEventHandler() {
        String connectionlessProtocolList = Context.getConfig().getString("status.ignoreOffline");
        if (connectionlessProtocolList != null) {
            connectionlessProtocols.addAll(Arrays.asList(connectionlessProtocolList.split("[, ]")));
        }
        logAttributes.addAll(Arrays.asList(
                Context.getConfig().getString("logger.attributes", DEFAULT_LOGGER_ATTRIBUTES).split("[, ]")));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Position) {

            Position position = (Position) msg;
            try {
                Context.getDeviceManager().updateLatestPosition(position);
            } catch (SQLException error) {
                LOGGER.warn("Failed to update device", error);
            }

            String uniqueId = Context.getIdentityManager().getById(position.getDeviceId()).getUniqueId();

            StringBuilder builder = new StringBuilder();
            builder.append(formatChannel(ctx.channel())).append(" ");
            builder.append("id: ").append(uniqueId);
            for (String attribute : logAttributes) {
                switch (attribute) {
                    case "time":
                        builder.append(", time: ").append(DateUtil.formatDate(position.getFixTime(), false));
                        break;
                    case "position":
                        builder.append(", lat: ").append(String.format("%.5f", position.getLatitude()));
                        builder.append(", lon: ").append(String.format("%.5f", position.getLongitude()));
                        break;
                    case "speed":
                        if (position.getSpeed() > 0) {
                            builder.append(", speed: ").append(String.format("%.1f", position.getSpeed()));
                        }
                        break;
                    case "course":
                        builder.append(", course: ").append(String.format("%.1f", position.getCourse()));
                        break;
                    case "accuracy":
                        if (position.getAccuracy() > 0) {
                            builder.append(", accuracy: ").append(String.format("%.1f", position.getAccuracy()));
                        }
                        break;
                    case "outdated":
                        if (position.getOutdated()) {
                            builder.append(", outdated");
                        }
                        break;
                    case "invalid":
                        if (!position.getValid()) {
                            builder.append(", invalid");
                        }
                        break;
                    default:
                        Object value = position.getAttributes().get(attribute);
                        if (value != null) {
                            builder.append(", ").append(attribute).append(": ").append(value);
                        }
                        break;
                }
            }
            //Output received information
//            System.out.println("receive info:" + builder.toString() + " " + battery);
//            System.out.println("receive info:" + position.getAttributes().get("battery"));
//            System.out.println("debug.xml:" + Context.getConfig().getString("output.api"));

            //Battery
            StringBuilder battery = new StringBuilder();
            if (position.getAttributes().get("batteryLevel") != null) {
                Double temp = Double.parseDouble(position.getAttributes().get("batteryLevel").toString());
                battery.append(Integer.parseInt(new java.text.DecimalFormat("0").format(temp)));
            } else {
                Double temp = (((Double.parseDouble(position.getAttributes().get("battery").toString()) - 3.3) / 0.9) * 100);
                battery.append(Integer.parseInt(new java.text.DecimalFormat("0").format(temp)));
            }

            //StandLat,StandLon
            Double standardLa = standardLat(position.getLatitude());
            Double standardLo = standardLon(position.getLongitude());

            int apiFlag = 1;

            //Output API
            HttpURLConnection conn = null;
            try {
                StringBuilder submitInfo = new StringBuilder(Context.getConfig().getString("output.api") + "/");
                submitInfo.append(position.getDeviceId() + "/" + uniqueId + "/" + String.format("%.6f", standardLa) + "/"
                        + String.format("%.6f", standardLo) + "/" + position.getSpeed());
                System.out.println("submitInfo:" + submitInfo);
                URL url = new URL(submitInfo.toString());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("accept", "*/");
                conn.setRequestProperty("connection", "Keep-Alive");
                conn.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestMethod("GET");
                conn.connect();
                conn.getInputStream();
            } catch (Exception e) {
                System.out.println("Can not link Api.");
                apiFlag = 0;
//                e.printStackTrace();
            } finally {
                conn.disconnect();
            }

            Date date = new Date();
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String upDate = format.format(date).toString();
            //Update table device
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                String url = "jdbc:mysql://localhost:3306/FASTGps?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true&autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8&sessionVariables=sql_mode=''&serverTimezone=UTC";
                String user = "root";
                String pwd = "123456";
                Connection connection = DriverManager.getConnection(url, user, pwd);
                String sql = "UPDATE FASTGps.tc_devices SET longitude='" + position.getLongitude() + "',latitude='"
                        + position.getLatitude() + "', standardLat='" + String.format("%.6f", standardLa)
                        + "', standardLon='" + String.format("%.6f", standardLo) + "', apiResult='" + apiFlag
                        + "', apiTime='" + upDate + "', battery='" + battery + "' WHERE uniqueid='" + uniqueId + "';";
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.execute();
                connection.close();
                System.out.println("Update devices success.");
            } catch (ClassNotFoundException | SQLException e) {
                e.printStackTrace();
            }

            LOGGER.info(builder.toString());

            Main.getInjector().getInstance(StatisticsManager.class)
                    .registerMessageStored(position.getDeviceId(), position.getProtocol());
        }
    }

    private static String formatChannel(Channel channel) {
        return String.format("[%s]", channel.id().asShortText());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!(ctx.channel() instanceof DatagramChannel)) {
            LOGGER.info(formatChannel(ctx.channel()) + " connected");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOGGER.info(formatChannel(ctx.channel()) + " disconnected");
        closeChannel(ctx.channel());

        if (BasePipelineFactory.getHandler(ctx.pipeline(), HttpRequestDecoder.class) == null
                && !connectionlessProtocols.contains(ctx.pipeline().get(BaseProtocolDecoder.class).getProtocolName())) {
            Context.getConnectionManager().removeActiveDevice(ctx.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        LOGGER.warn(formatChannel(ctx.channel()) + " error", cause);
        closeChannel(ctx.channel());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            LOGGER.info(formatChannel(ctx.channel()) + " timed out");
            closeChannel(ctx.channel());
        }
    }

    private void closeChannel(Channel channel) {
        if (!(channel instanceof DatagramChannel)) {
            channel.close();
        }
    }

}
