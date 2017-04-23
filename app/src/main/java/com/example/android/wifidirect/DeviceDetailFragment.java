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

/**
 * A fragment that manages a particular peer and allows interaction with device
 * i.e. setting up network connection and transferring data.
 */
public class DeviceDetailFragment extends Fragment implements ConnectionInfoListener,WifiP2pManager.GroupInfoListener {

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
    public boolean isClientAddressAvailable = false;
    public TextView resultTextView;
    public static int parentIndex = 0;

    public static int groupMembersConnected = 0;
    public static boolean isGroupOwnerThread = false;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    public void setIsGroupOwnerThread(boolean b){
        isGroupOwnerThread = b;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clientAddresses = new String[devices];
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
        mContentView.findViewById(R.id.btn_StartServer).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //Start the file transfer service at the server
                        new GroupOwnerFileTransferThread().execute(clientAddresses);

                    }
                }
        );

        resultTextView = (TextView) mContentView.findViewById(R.id.resultData);
        return mContentView;
    }

    @Override
    public void onGroupInfoAvailable(WifiP2pGroup group) {
        Log.d(WiFiDirectActivity.TAG,"ONGROUPINFOAVAILABLE : Entered");
        if(group.getClientList().size() == devices){
            //all members have joined. start the communication between server and client
            if(group.isGroupOwner()){
                mContentView.findViewById(R.id.btn_StartServer).setVisibility(View.VISIBLE);
            }
        }

    }

    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {

        Log.d(WiFiDirectActivity.TAG,"ON Connection Info Available : Entered");
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        this.info = info;
        this.getView().setVisibility(View.VISIBLE);

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
            new PeerThread(getActivity(),mContentView.findViewById(R.id.group_client)).execute(groupOwnerIP);

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


    private class GroupOwnerFileTransferThread extends AsyncTask<String[],Void,Void>{


        private int noOfClients = devices - 1;

        @Override
        protected void onPreExecute() {
            resultTextView.setText("Group Owner File Transfer Service Starting");
        }

        @Override
        protected Void doInBackground(String[]... params) {
            String[] allDeviceIP = params[0];

            // Receiving file parts from all the clients

            while(noOfClients > 0){
                Socket socket = new Socket();

                try {
                    Log.d(WiFiDirectActivity.TAG,"GO Connecting to client "+clientAddresses[noOfClients]);
                    socket.connect(new InetSocketAddress(clientAddresses[noOfClients],mPortData),SOCKET_TIMEOUT);

                    Log.d(WiFiDirectActivity.TAG,"Successfully connected to the Client " + clientAddresses[noOfClients] );

                    DataInputStream dataIn = new DataInputStream(socket.getInputStream());
                    DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());

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

                    Log.d(WiFiDirectActivity.TAG, "GO: receiving file from client : "+clientAddresses[noOfClients] +" : " + rFile.getAbsolutePath());

                    if(copyFile(dataIn, new FileOutputStream(rFile),size)){
                        Log.d(WiFiDirectActivity.TAG,"File Received Successfully from client : "+clientAddresses[noOfClients]);
                    }else{
                        Log.d(WiFiDirectActivity.TAG,"File receiving Failed from Client : "+ clientAddresses[noOfClients]);
                    }

                    noOfClients--;

                    dataOut.close();
                    dataIn.close();
                    socket.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }

            //Copy server file part from FILE folder to SERVER Folder
            String sfileName = "D2DFile.part0";
            String destfileName = "ReceivedFile.chunk0";
            File serverFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Files/" + sfileName );
            File destFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+ destfileName);
            try {
                if(copyFiletoAnotherFolder(serverFile,destFile)){
                    Log.d(WiFiDirectActivity.TAG,"File Copied to : "+ destFile.getAbsolutePath());

                }else{
                    Log.d(WiFiDirectActivity.TAG,"File Copying Failed to : "+ destFile.getAbsolutePath());

                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            //Send all files to all the clients
            String inputLine;
            for(int client = 1;client < devices;client++){
                Socket socket = new Socket();
                try {

                    socket.connect(new InetSocketAddress(clientAddresses[client],mPortData),SOCKET_TIMEOUT);

                    Log.d(WiFiDirectActivity.TAG,"Successfully connected to the Client " + clientAddresses[client] );

                    DataInputStream dataIn = new DataInputStream(socket.getInputStream());
                    DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());

                    dataOut.writeUTF("SENDING");
                    dataOut.flush();

                    dataOut.writeInt(devices - 1); // No of parts
                    dataOut.flush();

                    for(int part = 0;part < devices;part++){
                        if(client != part){

                            //send the file part
                            String fileName = "ReceivedFile.chunk"+part;
                            File sFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Received/"+ fileName);
                            long size = sFile.length();
                            byte[] byteArray = new byte[(int) size];

                            FileInputStream fis = new FileInputStream(sFile);
                            BufferedInputStream bis = new BufferedInputStream(fis);
                            DataInputStream dis = new DataInputStream(bis);
                            dis.readFully(byteArray,0,byteArray.length);

                            Log.d(WiFiDirectActivity.TAG,"Sending data to the Client :" + fileName );

                            dataOut.writeInt(part);
                            dataOut.writeLong(size);
                            dataOut.flush();

                            dataOut.write(byteArray,0,byteArray.length);
                            dataOut.flush();

                            while ((inputLine = dataIn.readUTF()) != null){
                                if(inputLine.equalsIgnoreCase("END")){
                                    break;
                                }
                            }

                        }
                    }


                    dataOut.close();
                    dataIn.close();
                    socket.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }

                Log.d(WiFiDirectActivity.TAG,"File Send to client : "+clientAddresses[client]);


            }




            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            resultTextView.setText("File sharing success");
        }
    }


    private class GroupOwnerThread extends AsyncTask<Void,Void,Void>{

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
            resultTextView.setText("Group Owner Listening for clients to connect on control Port");
        }

        @Override
        protected Void doInBackground(Void... params) {
            int connections = 1;

            try {
                ServerSocket serverSocket = new ServerSocket(mPortControl);
                serverSocket.setReuseAddress(true);

                noOfClients = devices - 1;

                Thread[] threads = new Thread[noOfClients];
                int count = 0;

                while(noOfClients > 0) {
                    threads[count] = new Thread(new MultiServerThread(serverSocket.accept(),connections++));
                    threads[count].start();
                    noOfClients--;
                    count++;
                }
                serverSocket.close();

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
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            String textString = "";
            for(int j = 0; j < devices;j++){
                textString = textString + clientAddresses[j] + " \n ";
            }
            clients.setText("Clients Connected IP \n" + textString);
            resultTextView.setText("Received All IP Addresses at Server");

            new GroupOwnerFileTransferThread().execute(clientAddresses);

        }
    }


    private class PeerThread extends AsyncTask<String,Void,Void>{

        private Context context;
        private TextView clientIPText;
        private int index = 0;

        public PeerThread(Context con,View c){
            this.context = con;
            this.clientIPText = (TextView) c;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            resultTextView.setText("Peer Thread Listening at Client");
        }

        @Override
        protected Void doInBackground(String... params) {
            String groupOwnerIP = params[0];
            String clientIP = "0.0.0.0";
            String inputLine;

            // Pinging the server to get IP addresses
            Socket clientSocket = new Socket();

            try {
                Log.d(WiFiDirectActivity.TAG,"Start Wait of 10 seconds ");
                Thread.sleep(10000);
                clientSocket .setReuseAddress(true);
                clientSocket.connect((new InetSocketAddress(groupOwnerIP, mPortControl)), SOCKET_TIMEOUT);
                Log.d(WiFiDirectActivity.TAG,"Connected to GO Success");

                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader((clientSocket.getInputStream())));

                out.println("CLIENTIP");
                out.flush();

                while ((inputLine = in.readLine()) != null) {
                    clientIP = inputLine;
                    index = Integer.parseInt(in.readLine());
                    break;
                }
                Log.d(WiFiDirectActivity.TAG,"Received CLient IP " + clientIP);

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
                Log.d(WiFiDirectActivity.TAG,"Waiting for the server to connect to client and send the file part to GO");
                String fileName = "D2DFile.part"+index;
                File sFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Files/" + fileName);

                Log.d(WiFiDirectActivity.TAG,"Sending Client File to GO : " + sFile.getAbsolutePath());

                long size = sFile.length();
                byte[] byteArray = new byte[(int) size];

                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(sFile));
                DataInputStream dis = new DataInputStream(bis);
                dis.readFully(byteArray,0,byteArray.length);

                ServerSocket peerSocket = new ServerSocket(mPortData);
                peerSocket.setReuseAddress(true);

                Socket groupOwner = peerSocket.accept();

                Log.d(WiFiDirectActivity.TAG,"GO connection accepted, sending data");

                DataInputStream serverData = new DataInputStream(groupOwner.getInputStream());
                DataOutputStream clientData = new DataOutputStream(groupOwner.getOutputStream());

                while((inputLine = serverData.readUTF()) != null){
                    if(inputLine.equalsIgnoreCase("SENDDATA")){
                        clientData.writeInt(index);
                        clientData.writeLong(size);
                        clientData.write(byteArray,0,byteArray.length);
                        clientData.flush();
                        break;
                    }
                }

                clientData.flush();
                Log.d(WiFiDirectActivity.TAG,"Data send to GO");
                clientData.close();
                serverData.close();
                peerSocket.close();

            } catch (IOException e) {
                e.printStackTrace();
            }

            //waiting for the server to connect to client and get all the remaining parts from the server
            int rIndex = 0;
            int noOfparts = 0;

            try {
                Log.d(WiFiDirectActivity.TAG,"witing to get all the remaining parts from the server");
                ServerSocket peerSocket = new ServerSocket(mPortData);
                peerSocket.setReuseAddress(true);
                Socket groupOwner = peerSocket.accept();
                Log.d(WiFiDirectActivity.TAG,"GO connection accepted, receiving data");

                DataInputStream serverData = new DataInputStream(groupOwner.getInputStream());
                DataOutputStream outData = new DataOutputStream(groupOwner.getOutputStream());

                while((inputLine = serverData.readUTF()) != null){
                    if(inputLine.equalsIgnoreCase("SENDING")){
                        noOfparts = serverData.readInt();

                        Log.d(WiFiDirectActivity.TAG,"Peer Thread : Receiving Parts : NoOfParts = "+noOfparts);

                        for(int parts = 0;parts < noOfparts;parts++){
                            rIndex = serverData.readInt();
                            long size = serverData.readLong();

                            String fileName = "ReceivedFile.chunk"+rIndex;

                            File receivedFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+fileName);
                            File dirs = new File(receivedFile.getParent());
                            if(!dirs.exists()){
                                dirs.mkdirs();
                            }
                            receivedFile.createNewFile();

                            if(copyFile(serverData,new FileOutputStream(receivedFile),size)){
                                Log.d(WiFiDirectActivity.TAG,"File Part received Successfully");
                            }else {
                                Log.d(WiFiDirectActivity.TAG,"Failed to receive File Part from GO");
                            }
                            outData.writeUTF("END");
                        }
                        break;
                    }
                }
                Log.d(WiFiDirectActivity.TAG,"Received all data parts");
                serverData.close();
                peerSocket.close();

            } catch (IOException e) {
                e.printStackTrace();
            }

            //Copy the file of the client based on index to Received folder
            String sfileName = "D2DFile.part"+index;
            String destfileName = "D2DFile.copiedPart"+index;
            File clientFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Files/" + sfileName );
            File destFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Received/"+ destfileName);
            try {
                if(copyFiletoAnotherFolder(clientFile,destFile)){
                    Log.d(WiFiDirectActivity.TAG,"File Copied to : " + destFile.getAbsolutePath());
                }else{
                    Log.d(WiFiDirectActivity.TAG,"File Copying Failed to : "+ destFile.getAbsolutePath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            resultTextView.setText("Files successfully Received");
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

    public class MultiServerThread implements Runnable{
        private Socket socket = null;
        private int index ;

        public MultiServerThread(Socket s,int ind){
            this.socket = s;
            this.index = ind;
        }

        public void run(){
            String inputLine,clientIP;

            try {
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
            } catch (IOException e1) {
                e1.printStackTrace();
            }

        }
    }




}
