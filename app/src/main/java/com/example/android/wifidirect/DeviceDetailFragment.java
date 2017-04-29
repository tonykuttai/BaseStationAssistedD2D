/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.wifidirect;

import android.app.Activity;
import android.app.Fragment;
import android.app.ProgressDialog;
import android.content.Context;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.example.android.wifidirect.DeviceListFragment.DeviceActionListener;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fragment that manages a particular peer and allows interaction with device
 * i.e. setting up network connection and transferring data.
 */
public class DeviceDetailFragment extends Fragment implements ConnectionInfoListener {

    protected static final int CHOOSE_FILE_RESULT_CODE = 20;
    private View mContentView = null;
    private WifiP2pDevice device;
    private WifiP2pInfo info;
    ProgressDialog progressDialog = null;
    private static int mPortControl = 8988;
    private static int mPortData = 8989;
    private static final int SOCKET_TIMEOUT = 5000;
    public static int devices = 3;
    public String[] clientAddresses;
    public int clientsConnectedAddress;

    public TextView resultTextView;
    public TextView clientAddress;

    public static boolean isGroupOwnerThread = false;

    public boolean[] CLCONNECTED;

    // For storing the file transfer history
    public boolean[][] GOMAT;
    public boolean[] CLMAT;

    private ProgressDialog cProgress;
    private Handler cHandler;

    private final Lock[] clientlock = new Lock[devices];

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        for (int i = 0; i < clientlock.length; i++) {
            clientlock[i] = new ReentrantLock();
        }
        clientsConnectedAddress = 0;
    }

    public void setIsGroupOwnerThread(boolean b){
        isGroupOwnerThread = b;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clientAddresses = new String[devices];
        GOMAT = new boolean[devices][devices];
        CLMAT = new boolean[devices];
        CLCONNECTED = new boolean[devices];

        cHandler = new Handler();

        cProgress = new ProgressDialog(getActivity());
        cProgress.setMax(100);
        cProgress.setCancelable(false);
        cProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);

        for (int i = 0;i < devices;i++){
            CLMAT[i] = false;
            CLCONNECTED[i] = false;
            for (int j = 0;j < devices;j++){
                if(i != j){
                    GOMAT[i][j] = false;
                }else {
                    GOMAT[i][j] = true;
                }
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        mContentView = inflater.inflate(R.layout.device_detail, null);
        mContentView.findViewById(R.id.btn_connect).setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = device.deviceAddress;
                config.wps.setup = WpsInfo.PBC;
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                progressDialog = ProgressDialog.show(getActivity(), "Press back to cancel",
                        "Connecting to :" + device.deviceAddress, true, true
                        );
                ((DeviceActionListener) getActivity()).connect(config);

            }
        });

        mContentView.findViewById(R.id.btn_disconnect).setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        ((DeviceActionListener) getActivity()).disconnect();
                    }
                });

        resultTextView = (TextView) mContentView.findViewById(R.id.resultData);
        clientAddress = (TextView) mContentView.findViewById(R.id.group_client);
        return mContentView;
    }


    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {

        Log.d(WiFiDirectActivity.TAG,"ON Connection Info Available : Entered");
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        this.info = info;
        this.getView().setVisibility(View.VISIBLE);
        
        Thread peer = null;


        // The owner IP is now known.
        TextView view = (TextView) mContentView.findViewById(R.id.group_owner);
        view.setText(getResources().getString(R.string.group_owner_text) + ((info.isGroupOwner == true) ? getResources().getString(R.string.yes) : getResources().getString(R.string.no)));

        // InetAddress from WifiP2pInfo struct.
        view = (TextView) mContentView.findViewById(R.id.device_info);
        view.setText("Group Owner IP - " + info.groupOwnerAddress.getHostAddress());

        // After the group negotiation, we assign the group owner as the file
        // server. The file server is single threaded, single connection server
        // socket.
        if (info.groupFormed && info.isGroupOwner) {
            // here wait for the group members to connect and get their corresponding IP addresses
            Log.d(WiFiDirectActivity.TAG,"ON Connection Info Available : group formed and is group owner");

            if(!isGroupOwnerThread){
                Log.d(WiFiDirectActivity.TAG,"ON Connection Info Available : group owner Thread check");
                setIsGroupOwnerThread(true);
                clientAddresses[0] = info.groupOwnerAddress.getHostAddress();

                // Async task for server Getting the IP addresses of client : Listening to the server connections
                new GroupOwnerThread(getActivity(), mContentView.findViewById(R.id.group_client)).execute();

            }

        } else if (info.groupFormed) {
            // The other device acts as the client. In this case, we enable the
            // get file button.

            Log.d(WiFiDirectActivity.TAG,"ON Connection Info Available : Group formed Not group owner");
            String groupOwnerIP = info.groupOwnerAddress.getHostAddress();

            //Start an AsyncTask for groupMember
            new GroupMemberTask((getActivity())).execute(groupOwnerIP);
           /* peer =  new Thread(new GroupMemberThread(getActivity(),groupOwnerIP));
            peer.start();

            try {
                peer.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }*/

        }
        // hide the connect button
        mContentView.findViewById(R.id.btn_connect).setVisibility(View.GONE);

    }

    /**
     * Updates the UI with device data
     * 
     * @param device the device to be displayed
     */
    public void showDetails(WifiP2pDevice device) {
        this.device = device;
        this.getView().setVisibility(View.VISIBLE);
        TextView view = (TextView) mContentView.findViewById(R.id.device_address);
        view.setText(device.deviceAddress);
        view = (TextView) mContentView.findViewById(R.id.device_info);
        view.setText(device.toString());

    }

    /**
     * Clears the UI fields after a disconnect oer direct mode disable operation.
     */
    public void resetViews() {
        mContentView.findViewById(R.id.btn_connect).setVisibility(View.VISIBLE);
        TextView view = (TextView) mContentView.findViewById(R.id.device_address);
        view.setText(R.string.empty);
        view = (TextView) mContentView.findViewById(R.id.device_info);
        view.setText(R.string.empty);
        view = (TextView) mContentView.findViewById(R.id.group_owner);
        view.setText(R.string.empty);
        view = (TextView) mContentView.findViewById(R.id.status_text);
        view.setText(R.string.empty);
       // mContentView.findViewById(R.id.btn_start_client).setVisibility(View.GONE);
        this.getView().setVisibility(View.GONE);
    }

    private class GroupOwnerThread extends AsyncTask<Void,String[],Void>{

        private Context context;
        private TextView clients;
        private int noOfClients = devices - 1;
        private int index = 0;


        public GroupOwnerThread (Context context,View clients) {
            this.context = context;
            this.clients = (TextView) clients;
        }

        @Override
        protected void onPreExecute() {
            resultTextView.setText("Group Owner Listening for clients to connect");
        }

        @Override
        protected Void doInBackground(Void... params) {
            int connections = 1;

            try {
                Log.d(WiFiDirectActivity.TAG,"AsyncTask Group Owner Thread Running");

                ServerSocket serverSocket = new ServerSocket(mPortControl);
                serverSocket.setReuseAddress(true);

                Thread[] threads = new Thread[noOfClients];
                int count = 0;

                while(noOfClients > 0) {

                    threads[count] = new Thread(new GOClientHandler(serverSocket.accept(),connections++));
                    threads[count].start();
                    noOfClients--;
                    count++;
                }
                serverSocket.close();

                while(clientsConnectedAddress < (devices - 1));

                publishProgress(clientAddresses);

                for (Thread thread : threads){
                    thread.join();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(String[]... values) {
            super.onProgressUpdate(values);
            String textString = "";
            for(int j = 0; j < devices;j++){
                textString = textString + clientAddresses[j] + " \n ";
            }
            clients.setText("Clients Connected : File transfer in Progress \n" + textString);
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            String textString = "";
            for(int j = 0; j < devices;j++){
                textString = textString + clientAddresses[j] + " \n ";
            }
            clients.setText("Clients :" + textString);
            resultTextView.setText("File parts Successfully Shared with all the devices");
        }
    }

    private class GroupMemberTask extends AsyncTask<String,String,String[]>{
        private Context context;
        private int cIndex = 0;
        private String groupOwnerIP;
        private String fileName;
        String clientIP = "0.0.0.0";
        String inputLine = "";
        String[] receivedFiles = new String[devices];
        int noOfMissing = 0;
        int rIndex = 0;

        public GroupMemberTask(Context context) {
            super();
            this.context = context;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            resultTextView.setText("GroupMember Connecting to the GO");
        }

        @Override
        protected String[] doInBackground(String... params) {
            groupOwnerIP = params[0];
            for(int q = 0;q < devices;q++){
                receivedFiles[q] = "";
            }
            // Pinging the server to get IP addresses
            Socket clientSocket = new Socket();

            try {
                Log.d(WiFiDirectActivity.TAG,"Start Wait of 3 seconds ");
                Thread.sleep(3000);
                clientSocket .setReuseAddress(true);
                clientSocket.connect((new InetSocketAddress(groupOwnerIP, mPortControl)), SOCKET_TIMEOUT);
                Log.d(WiFiDirectActivity.TAG,"Connected to GO Success");

                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader((clientSocket.getInputStream())));

                publishProgress("STARTCLIENTIP");

                out.println("CLIENTIP");
                out.flush();

                while ((inputLine = in.readLine()) != null) {
                    clientIP = inputLine;
                    cIndex = Integer.parseInt(in.readLine());
                    break;
                }
                Log.d(WiFiDirectActivity.TAG,"Received Client IP " + clientIP + " Index :" + cIndex);

                publishProgress("STOPCLIENTIP");
                out.close();
                in.close();
                clientSocket.close();

            } catch (SocketException e) {
                Log.d(WiFiDirectActivity.TAG,"Socket connection to Server failed");
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            //Waiting for the server to connect to client and send the file part to GO

            try {
                ServerSocket peerSocket = new ServerSocket(mPortData);
                peerSocket.setReuseAddress(true);

                Socket groupOwner = peerSocket.accept();
                Log.d(WiFiDirectActivity.TAG,"GO connection accepted, sending data");

                Log.d(WiFiDirectActivity.TAG,"Waiting for the server to connect to client and send the file part to GO");
                fileName = "ReceivedFile.chunk";
                File sFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/MobileData/" + fileName);

                receivedFiles[cIndex] = fileName; //storing the received file Name

                Log.d(WiFiDirectActivity.TAG,"Preparing Client File : " + sFile.getAbsolutePath());

                long size = sFile.length();
                byte[] byteArray = new byte[(int) size];

                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(sFile));
                DataInputStream dis = new DataInputStream(bis);
                dis.readFully(byteArray,0,byteArray.length);

                DataInputStream serverData = new DataInputStream(groupOwner.getInputStream());
                DataOutputStream clientData = new DataOutputStream(groupOwner.getOutputStream());

                publishProgress("STARTSENDCLIENTFILE");

                while((inputLine = serverData.readUTF()) != null){
                    if(inputLine.equalsIgnoreCase("SENDDATA")){
                        clientData.writeInt(cIndex);
                        clientData.writeLong(size);
                        clientData.write(byteArray,0,byteArray.length);
                        clientData.flush();
                        break;
                    }
                }
                publishProgress("STOPSENDCLIENTFILE");

                clientData.flush();
                Log.d(WiFiDirectActivity.TAG,"Client Data send to GO");


                //Receiving the GO File part from GO

                while ((inputLine = serverData.readUTF()) != null){
                    if(inputLine.equalsIgnoreCase("GOFILEREADY")){
                        size = serverData.readLong();
                        fileName = "ReceivedFile.chunk0";

                        File receivedFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+fileName);
                        File dirs = new File(receivedFile.getParent());
                        if(!dirs.exists()){
                            dirs.mkdirs();
                        }
                        receivedFile.createNewFile();
                        publishProgress("STARTRECEIVEGOFILE");

                        if(copyFile(serverData,new FileOutputStream(receivedFile),size)){
                            Log.d(WiFiDirectActivity.TAG,"File Part received Successfully");
                            receivedFiles[0] = fileName;
                            publishProgress("STOPRECEIVEGOFILE");
                        }else {
                            Log.d(WiFiDirectActivity.TAG,"Failed to receive File Part from GO");
                            publishProgress("ERRORRECEIVEGOFILE");
                        }


                    }else if(inputLine.equalsIgnoreCase("ENDGOFILE")){
                        Log.d(WiFiDirectActivity.TAG,"ENDING GO FILE Transmission");
                        break;
                    }
                }



                //Receive Missing files from the server
                while ((inputLine = serverData.readUTF()) != null){
                    if(inputLine.equalsIgnoreCase("MISSINGPARTS")){
                        noOfMissing = serverData.readInt();

                        publishProgress("STARTRECEIVEMISSINGFILES");

                        for(int miss = 1; miss <= noOfMissing; miss++ ){
                            rIndex = serverData.readInt();
                            size = serverData.readLong();

                            fileName = "ReceivedFile.chunk"+rIndex;
                            File receivedFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+fileName);
                            File dirs = new File(receivedFile.getParent());
                            if(!dirs.exists()){
                                dirs.mkdirs();
                            }
                            receivedFile.createNewFile();


                            if(copyFile(serverData,new FileOutputStream(receivedFile),size)){
                                receivedFiles[rIndex] = fileName;
                                Log.d(WiFiDirectActivity.TAG,"File Part received Successfully");
                                publishProgress("STOPRECEIVEMISSINGFILES");
                            }else {
                                Log.d(WiFiDirectActivity.TAG,"Failed to receive File Part from GO");
                                publishProgress("ERRORRECEIVEMISSINGFILES");
                            }



                        }

                        if(serverData.readUTF().equalsIgnoreCase("ENDMISSINGPARTS")){
                            clientData.writeUTF("END");
                            clientData.flush();
                            break;
                        }


                    }else if(inputLine.equalsIgnoreCase("NOMISSINGPARTS")){
                        break;
                    }
                }

                clientData.close();
                serverData.close();
                peerSocket.close();

            } catch (IOException e) {
                e.printStackTrace();
            }


            // Wait for the server to connect and transfer the missing file
            for(int waitServer = 0; waitServer < (devices - cIndex - 1);waitServer++) {
                try {
                    ServerSocket peerSocket = new ServerSocket(mPortData);
                    peerSocket.setReuseAddress(true);
                    publishProgress("WAITINGFORADDITIONALCLIENTFILES");
                    Socket groupOwner = peerSocket.accept();
                    Log.d(WiFiDirectActivity.TAG, "GO connection accepted, Preparing to receive data");

                    DataInputStream dataIn = new DataInputStream(groupOwner.getInputStream());
                    DataOutputStream dataOut = new DataOutputStream(groupOwner.getOutputStream());

                    while ((inputLine = dataIn.readUTF()) != null) {
                        if (inputLine.equalsIgnoreCase("RESTDATA")) {
                            rIndex = dataIn.readInt();
                            long size = dataIn.readLong();

                            fileName = "ReceivedFile.chunk" + rIndex;
                            File receivedFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/" + fileName);
                            File dirs = new File(receivedFile.getParent());
                            if (!dirs.exists()) {
                                dirs.mkdirs();
                            }
                            receivedFile.createNewFile();

                            publishProgress("NEWCLIENTADDITIONALFILE");
                            if (copyFile(dataIn, new FileOutputStream(receivedFile), size)) {
                                Log.d(WiFiDirectActivity.TAG, "File Part received Successfully");
                                receivedFiles[rIndex] = fileName;
                                publishProgress("NEWCLIENTADDITIONALFILESUCCESS");
                            } else {
                                Log.d(WiFiDirectActivity.TAG, "Failed to receive File Part from GO");
                                publishProgress("NEWCLIENTADDITIONALFILEERROR");
                            }


                        } else if (inputLine.equalsIgnoreCase("ENDRESTDATA")) {
                            dataOut.writeUTF("END");
                            dataOut.flush();
                            break;
                        }
                    }

                    dataIn.close();
                    dataOut.close();
                    peerSocket.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
            File folder = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/");
            File[] listOfFiles = folder.listFiles();
            String[] receivedFiles = new String[devices - 1];
            int count = 0;
            for(File f : listOfFiles){
                receivedFiles[count++] = f.getName();
            }

            return receivedFiles;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            String progress = values.toString();
            if(progress.equalsIgnoreCase("STARTCLIENTIP")){
                resultTextView.setText("Client IP service Initiating");
            }else if(progress.equalsIgnoreCase("STOPCLIENTIP")){
                clientAddress.setText("Current IP : "+ clientIP);
                resultTextView.append("\n Client IP Received");
            }else if(progress.equalsIgnoreCase("STARTSENDCLIENTFILE")){
                resultTextView.append("\n Sending Client File to GO");
            }else if(progress.equalsIgnoreCase("STOPSENDCLIENTFILE")){
                resultTextView.append("\n Client File Successfully send to GO");
            }else if (progress.equalsIgnoreCase("STARTRECEIVEGOFILE")){
                resultTextView.append("\n Receiving GO File ");
            }else if(progress.equalsIgnoreCase("STOPRECEIVEGOFILE")){
                resultTextView.append("\n GO file received successfully");
            }else if(progress.equalsIgnoreCase("ERRORRECEIVEGOFILE")){
                resultTextView.append("\n Error in receiving GO file");
            }else if(progress.equalsIgnoreCase("STARTRECEIVEMISSINGFILES")){
                resultTextView.append("\n Receiving Missing Files from GO");
            }else if(progress.equalsIgnoreCase("STOPRECEIVEMISSINGFILES")){
                resultTextView.append("\n Missing Files received from GO");
            }else if(progress.equalsIgnoreCase("ERRORRECEIVEMISSINGFILES")){
                resultTextView.append("\n Error in receiving Missing Files from GO!!!");
            }else if(progress.equalsIgnoreCase("WAITINGFORADDITIONALCLIENTFILES")){
                resultTextView.append("\n Waiting for New Clients to join the group and transfer Files");
            }else if(progress.equalsIgnoreCase("NEWCLIENTADDITIONALFILE")){
                resultTextView.append("\n New Client connected. Transferring File");
            }else if(progress.equalsIgnoreCase("NEWCLIENTADDITIONALFILESUCCESS")){
                resultTextView.append("\n New Client Transferring File Success");
            }else if(progress.equalsIgnoreCase("NEWCLIENTADDITIONALFILEERROR")){
                resultTextView.append("\n New Client Transferring File Error!!");
            }
        }

        @Override
        protected void onPostExecute(String[] strings) {
            resultTextView.setText("File Transfer Completed Successfully");
            for(String file : strings){
                resultTextView.append("\n "+file);
            }
        }
    }

    public static boolean copyFile(DataInputStream inputStream, OutputStream out,long size) {
        byte buf[] = new byte[1024];
        int bytesRead;

        try {
            while ((size > 0) && (bytesRead = inputStream.read(buf,0,(int)Math.min(buf.length,size))) != -1) {
                out.write(buf, 0, bytesRead);
                size -= bytesRead;
            }
            out.close();
        } catch (IOException e) {
            Log.d(WiFiDirectActivity.TAG, e.toString());
            return false;
        }
        return true;
    }

    public static boolean copyFiletoAnotherFolder(File source,File dest)throws  IOException{
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);

            byte[] buffer = new byte[1024];
            int length = 0;
            while((length = is.read(buffer)) > 0){
                os.write(buffer,0,length);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }finally {
            is.close();
            os.close();
        }

        return true;

    }

    public class GOClientHandler implements Runnable{
        private Socket socket = null;
        private int index;
        String inputLine = "";
        String clientIP = "";

        public GOClientHandler(Socket s,int ind){
            this.socket = s;
            this.index = ind;
        }

        @Override
        public void run() {

            //Get the client IP and send it back
            clientlock[index].lock();
            try {
                Log.d(WiFiDirectActivity.TAG,"GOClient Handler : client Index "+ index);
                CLCONNECTED[index] = true;
                PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                while((inputLine = in.readLine()) != null){
                    if(inputLine.equalsIgnoreCase("CLIENTIP")){
                        //Send the Client IP Address
                        clientIP = socket.getInetAddress().getHostAddress();
                        out.println(clientIP);
                        out.flush();
                        out.println(index);
                        out.flush();
                        clientAddresses[index] = clientIP;
                        break;
                    }
                }

                out.close();
                in.close();
                socket.close();
                Log.d(WiFiDirectActivity.TAG,"GOClient Handler : client Index "+ index + " \n Received Client IP" );
                clientsConnectedAddress++; //for displaying the clients connected IP in the UI Thread
                Thread.sleep(2000);

                //Receiving the Client chunk
                Socket cSocket = new Socket();
                Log.d(WiFiDirectActivity.TAG,"client Index : "+ index + " GO Connecting to client "+clientIP);
                cSocket.connect(new InetSocketAddress(clientIP,mPortData));

                Log.d(WiFiDirectActivity.TAG,"client Index : "+ index + "Successfully Connected to the Client " + clientIP);

                DataInputStream dataIn = new DataInputStream(cSocket.getInputStream());
                DataOutputStream dataOut = new DataOutputStream(cSocket.getOutputStream());

                dataOut.writeUTF("SENDDATA");
                dataOut.flush();

                int rIndex = (int) dataIn.readInt();
                long size = dataIn.readLong();

                String fileName = "ReceivedFile.chunk"+rIndex;

                File rFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+ fileName);
                File dirs = new File(rFile.getParent());
                if (!dirs.exists())
                    dirs.mkdirs();
                rFile.createNewFile();

                Log.d(WiFiDirectActivity.TAG, "client Index : "+ index + " GO: receiving file from client : "+clientIP +" : " + rFile.getAbsolutePath());

                if(copyFile(dataIn, new FileOutputStream(rFile),size)){
                    Log.d(WiFiDirectActivity.TAG,"client Index : "+ index + " File Received Successfully from client : "+clientIP);
                    GOMAT[0][index] = true;
                }else{
                    Log.d(WiFiDirectActivity.TAG,"client Index : "+ index + " File receiving Failed from Client : "+ clientIP);
                    GOMAT[0][index] = false;
                }


                // Send the GO file to the client
                Log.d(WiFiDirectActivity.TAG,"client Index : "+ index + " Sending GO file to the Client");
                dataOut.writeUTF("GOFILEREADY");
                dataOut.flush();

                String gofileName = "ReceivedFile.chunk";
                File goFile = new File(Environment.getExternalStorageDirectory()+ "/WifiNetworking/" + "/MobileData/" + gofileName);

                size = goFile.length();
                byte[] byteArray = new byte[(int) size];

                FileInputStream fis = new FileInputStream(goFile);
                BufferedInputStream bis = new BufferedInputStream(fis);
                DataInputStream dis = new DataInputStream(bis);
                dis.readFully(byteArray,0,byteArray.length);

                Log.d(WiFiDirectActivity.TAG,"client Index : "+ index +"Sending GO File to the Client : "+clientIP +"  File Name :"+ gofileName );

                dataOut.writeLong(size);
                dataOut.flush();

                dataOut.write(byteArray,0,byteArray.length);
                dataOut.flush();

                dataOut.writeUTF("ENDGOFILE");
                dataOut.flush();

                Log.d(WiFiDirectActivity.TAG,"client Index : "+ index +"Sending GO File to the Client : "+clientIP +"  File Name :"+ gofileName +" Success" );

                GOMAT[index][0] = true;

                //Send missing file parts to the client

                if(index > 1){
                    Log.d(WiFiDirectActivity.TAG,"client Index : "+ index +"Sending Missing File Parts to the Client : "+clientIP);

                    dataOut.writeUTF("MISSINGPARTS");
                    dataOut.flush();

                    dataOut.writeInt(index - 1); //No of missing parts
                    dataOut.flush();

                    for (int miss = 1;miss < index;miss++){
                        if(GOMAT[index][miss] == false){
                            //File missing at the client .Send the files
                            fileName = "ReceivedFile.chunk"+miss;
                            File cFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Received/"+ fileName);
                            long cSize = cFile.length();
                            byte[] cByteArray = new byte[(int) cSize];

                            FileInputStream cfis = new FileInputStream(cFile);
                            BufferedInputStream cbis = new BufferedInputStream(cfis);
                            DataInputStream cdis = new DataInputStream(cbis);
                            cdis.readFully(cByteArray,0,cByteArray.length);

                            Log.d(WiFiDirectActivity.TAG,"Sending missing data to the Client :" + fileName );

                            dataOut.writeInt(miss);
                            dataOut.writeLong(cSize);
                            dataOut.flush();

                            dataOut.write(cByteArray,0,cByteArray.length);
                            dataOut.flush();

                            GOMAT[index][miss] = true;
                        }
                    }

                    dataOut.writeUTF("ENDMISSINGPARTS");
                    dataOut.flush();

                    while ((inputLine = dataIn.readUTF()) != null){
                        if(inputLine.equalsIgnoreCase("END")){
                            break;
                        }
                    }
                }else{
                    dataOut.writeUTF("NOMISSINGPARTS");
                    dataOut.flush();
                }

                dataOut.close();
                dataIn.close();
                cSocket.close();
                CLCONNECTED[index] = false; //Client disconnected

            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }finally {
                clientlock[index].unlock();
            }

            boolean allTransferred = false;
            int tranCount = 0;

            //Initiate connection to other clients and transfer the missing file ie current index file
            String fileName = "ReceivedFile.chunk"+index;
            File cFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Received/"+ fileName);
            long cSize = cFile.length();
            byte[] cByteArray = new byte[(int) cSize];

            FileInputStream cfis = null;
            try {
                cfis = new FileInputStream(cFile);

            BufferedInputStream cbis = new BufferedInputStream(cfis);
            DataInputStream cdis = new DataInputStream(cbis);
            cdis.readFully(cByteArray,0,cByteArray.length);


            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }


                Log.d(WiFiDirectActivity.TAG,"Client Index " + index + " Transferring the files to other clients: while loop");
                for (int cMiss = 1; cMiss < index;cMiss++){
                    if(GOMAT[cMiss][index] == false){
                        Socket mSocket = new Socket();
                        clientlock[cMiss].lock();
                        try {
                            Log.d(WiFiDirectActivity.TAG,"Client Index :" + index + " Connecting to client " + clientAddresses[cMiss]);
                            mSocket.connect(new InetSocketAddress(clientAddresses[cMiss],mPortData),SOCKET_TIMEOUT);
                            Log.d(WiFiDirectActivity.TAG,"Successfully Connected to client " + clientAddresses[cMiss]);

                            CLCONNECTED[cMiss] = true;
                            DataInputStream dataIn = new DataInputStream(mSocket.getInputStream());
                            DataOutputStream dataOut = new DataOutputStream(mSocket.getOutputStream());

                            dataOut.writeUTF("RESTDATA");
                            dataOut.flush();

                            Log.d(WiFiDirectActivity.TAG,"Sending REST data to the Client :"+clientAddresses[cMiss] +" : file : " +fileName );

                            dataOut.writeInt(index);
                            dataOut.writeLong(cSize);
                            dataOut.flush();

                            dataOut.write(cByteArray,0,cByteArray.length);
                            dataOut.flush();

                            GOMAT[cMiss][index] = true;
                            dataOut.writeUTF("ENDRESTDATA");
                            dataOut.flush();

                            while ((inputLine = dataIn.readUTF()) != null){
                                if(inputLine.equalsIgnoreCase("END")){
                                    break;
                                }
                            }

                            Log.d(WiFiDirectActivity.TAG,"Sending REST data to the Client :"+clientAddresses[cMiss] +" Success : " +fileName );
                            dataOut.close();
                            dataIn.close();
                            mSocket.close();
                            tranCount++;
                            CLCONNECTED[cMiss] = false;


                        } catch (IOException e) {
                            e.printStackTrace();
                        }finally {
                            clientlock[cMiss].unlock();
                        }
                    }

                }

            Log.d(WiFiDirectActivity.TAG,"Client : index "+ index + "GOCLIENTHANDLER EXITING");

        }
    }


}
