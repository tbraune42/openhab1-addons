/**
 * Copyright (c) 2010-2018, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package be.devlaminck.openwebnet;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.myhome.fcrisciani.connector.MyHomeJavaConnector;
import com.myhome.fcrisciani.exception.MalformedCommandOPEN;

/**
 * OpenWebNet - OpenHab device communicator Based on code from Mauro Cicolella
 * (as part of the FREEDOMOTIC framework)
 * (https://github.com/freedomotic/freedomotic
 * /tree/master/plugins/devices/openwebnet) and on code of Flavio Fcrisciani
 * released as EPL (https://github.com/fcrisciani/java-myhome-library)
 *
 * @author Tom De Vlaminck, Andrea Carabillo
 * @author Reinhard Freuis - various enhancements for heating, rollershutter
 * @serial 1.0
 * @since 1.7.0
 *
 */
public class OpenWebNet extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(OpenWebNet.class);

    /*
     * Initializations
     */
    private String host = "";
    // standard port for the MH200(N) of bticino
    private Integer port = 20000;
    private String passwd = "";
    private Date m_last_bus_scan = new Date(0);
    private Integer m_bus_scan_interval_secs = 120;
    private Integer m_first_scan_delay_secs = 60;
    private Integer m_heating_zones = 0;
    private Integer m_shutter_run_msecs = 0;
    public MyHomeJavaConnector myPlant = null;
    private MonitorSessionThread monitorSessionThread = null;

    /*
     * OWN Diagnostic Frames
     */
    private final static String LIGHTING_DIAGNOSTIC_FRAME = "*#1*0##";
    private final static String AUTOMATIONS_DIAGNOSTIC_FRAME = "*#2*0##";
    private final static String ALARM_DIAGNOSTIC_FRAME = "*#5##";
    private final static String POWER_MANAGEMENT_DIAGNOSTIC_FRAME = "*#3##";

    /*
     * Event listeners = they receive an object when something happens on the
     * bus
     */
    private List<IBticinoEventListener> m_event_listener_list = new LinkedList<IBticinoEventListener>();

    public OpenWebNet(String p_host, int p_port, int p_rescan_interval_secs) {
        this(p_host, p_port, "", p_rescan_interval_secs);
    }

    public OpenWebNet(String p_host, int p_port, int p_rescan_interval_secs, int p_heating_zones,
            int p_shutter_run_msecs) {
        this(p_host, p_port, "", p_rescan_interval_secs, p_heating_zones, p_shutter_run_msecs);
    }

    public OpenWebNet(String p_host, int p_port, String p_passwd, int p_rescan_interval_secs) {
        host = p_host;
        port = p_port;
        passwd = p_passwd;
        m_bus_scan_interval_secs = p_rescan_interval_secs;
    }

    public OpenWebNet(String p_host, int p_port, String p_passwd, int p_rescan_interval_secs, int p_heating_zones,
            int p_shutter_run_msecs) {
        host = p_host;
        port = p_port;
        passwd = p_passwd;
        m_bus_scan_interval_secs = p_rescan_interval_secs;
        m_heating_zones = p_heating_zones;
        m_shutter_run_msecs = p_shutter_run_msecs;
    }

    /*
     * Sensor side
     */
    public void onStart() {
        // create thread
        monitorSessionThread = new MonitorSessionThread(this, host, port, passwd);
        // start first bus scan 30 secs later
        m_last_bus_scan = new Date(
                (new Date()).getTime() - (1000 * m_bus_scan_interval_secs) + (1000 * m_first_scan_delay_secs));
        // start thread
        monitorSessionThread.start();
        logger.info(
                "Connected to [{}:{}], Rescan bus every [{}] seconds, first scan over [{}] seconds, max. heating zones: [{}], Shutter run time [{}] msecs",
                host, port, m_bus_scan_interval_secs, (((new Date()).getTime() - m_last_bus_scan.getTime()) / 1000),
                m_heating_zones, m_shutter_run_msecs);
        // start the processing thread
        start();
    }

    /*
     * Actuator side
     */
    public void onCommand(ProtocolRead c) throws IOException, Exception {
        try {
            myPlant.sendCommandAsync(OWNUtilities.createFrame(c), 1);
        } catch (MalformedCommandOPEN ex) {
            logger.warn("onCommand error : {}", ex.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            while (!Thread.interrupted()) {
                // synchronizes the software with the system status
                // Every x seconds do a full bus scan
                checkForBusScan();
                Thread.sleep(1000);
            }
        } catch (InterruptedException p_i_ex) {
            logger.error("Openwebnet.run, InterruptedException : " + p_i_ex.getMessage());
        } catch (Exception p_i_ex) {
            logger.warn("Openwebnet.run, Exception : {}", p_i_ex.getMessage());
        } finally {
            // interrupt handler on monitor thread will stop thread
            monitorSessionThread.interrupt();
            logger.info("Stopped monitorSessionThread thread");
        }
        logger.info("Stopped OpenWebNet thread");
    }

    private void checkForBusScan() {
        Date l_now = new Date();
        if (((l_now.getTime() - m_last_bus_scan.getTime()) / 1000) > m_bus_scan_interval_secs) {
            m_last_bus_scan = l_now;
            initSystem();
        }
    }

    // sends diagnostic frames to initialize the system
    public void initSystem() {
        try {
            logger.info("Sending frames to (re)initialize subsystems");

            logger.debug("Sending {} frame to (re)initialize LIGHTING", LIGHTING_DIAGNOSTIC_FRAME);
            myPlant.sendCommandSync(LIGHTING_DIAGNOSTIC_FRAME);
            logger.debug("Sending {} frame to (re)initialize AUTOMATIONS", AUTOMATIONS_DIAGNOSTIC_FRAME);
            myPlant.sendCommandSync(AUTOMATIONS_DIAGNOSTIC_FRAME);
            logger.debug("Sending {} frame to (re)initialize ALARM", ALARM_DIAGNOSTIC_FRAME);
            myPlant.sendCommandSync(ALARM_DIAGNOSTIC_FRAME);
            logger.debug("Sending {} frame to (re)initialize POWER MANAGEMENT", POWER_MANAGEMENT_DIAGNOSTIC_FRAME);
            myPlant.sendCommandSync(POWER_MANAGEMENT_DIAGNOSTIC_FRAME);

            // *#4*#1*20## Diagnostic Frame Actors
            logger.debug("Sending Diagnostic Frame Actors to (re)initialize HEATING");
            myPlant.sendCommandSync("*#4*#1*20##");

            // *#4*#0## Diagnostic Frame Program Main Unit
            logger.debug("Sending Diagnostic Frame Main Unit to (re)initialize HEATING");
            myPlant.sendCommandSync("*#4*#0##");

            Calendar rightNow = Calendar.getInstance();

            if (m_heating_zones > 0 && rightNow.get(Calendar.MINUTE) == 59) {
                logger.debug("Sending Diagnostic Frame Heat Zones 1 .. {} to (re)initialize HEATING", m_heating_zones);
                for (int i = 1; i <= m_heating_zones; i++) {
                    logger.debug("Sending Diagnostic Frame Heat Zone {} to (re)initialize HEATING", i);
                    // *4#*#xx## Diagnostic Frame Main Unit
                    myPlant.sendCommandSync("*#4*#" + i + "##");
                    // *4#*xx## Diagnostic Frame Thermostat
                    myPlant.sendCommandSync("*#4*" + i + "##");
                }
                logger.debug("Sending Diagnostic Frames to (re)initialize HEATING finished");
            }
        } catch (Exception e) {
            logger.warn("initSystem failed : {}", e.getMessage());
        }
    }

    public void notifyEvent(ProtocolRead p_i_event) {
        for (IBticinoEventListener l_event_listener : m_event_listener_list) {
            try {
                l_event_listener.handleEvent(p_i_event);
            } catch (Exception p_ex) {
                logger.warn("notifyEvent, Exception : {}", p_ex.getMessage());
            }
        }
    }

    public void addEventListener(IBticinoEventListener p_i_event_listener) {
        m_event_listener_list.add(p_i_event_listener);
    }

    public void removeEventListener(IBticinoEventListener p_i_event_listener) {
        m_event_listener_list.remove(p_i_event_listener);
    }

}
