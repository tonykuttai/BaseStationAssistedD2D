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
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

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
    public String[] clientAddresses = new String[3];
    public String[] sampleData = new String[3];
    public String[] receiveData = new String[3];
    public static int devices = 2;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
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
//                        new DialogInterface.OnCancelListener() {
//
//                            @Override
//                            public void onCancel(DialogInterface dialog) {
//                                ((DeviceActionListener) getActivity()).cancelDisconnect();
//                            }
//                        }
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

        mContentView.findViewById(R.id.btn_start_client).setOnClickListener(
                new View.OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        // Allow user to pick an image from Gallery or other
                        // registered apps
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("image/*");
                        startActivityForResult(intent, CHOOSE_FILE_RESULT_CODE);
                    }
                });

        sampleData[0] = "Who";
        sampleData[1] = "are";
        sampleData[2] = "you";

        return mContentView;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        // User has picked an image. Transfer it to group owner i.e peer using
        // FileTransferService.
        Uri uri = data.getData();
        TextView statusText = (TextView) mContentView.findViewById(R.id.status_text);
        statusText.setText("Sending: " + uri);
        Log.d(WiFiDirectActivity.TAG, "Intent----------- " + uri);
        Intent serviceIntent = new Intent(getActivity(), FileTransferService.class);
        serviceIntent.setAction(FileTransferService.ACTION_SEND_FILE);
        serviceIntent.putExtra(FileTransferService.EXTRAS_FILE_PATH, uri.toString());
        serviceIntent.putExtra(FileTransferService.EXTRAS_GROUP_OWNER_ADDRESS,
                info.groupOwnerAddress.getHostAddress());
        serviceIntent.putExtra(FileTransferService.EXTRAS_GROUP_OWNER_PORT, 8988);
        getActivity().startService(serviceIntent);
    }

    @Override
    public void onConnectionInfoAvailable(final WifiP2pInfo info) {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        this.info = info;
        this.getView().setVisibility(View.VISIBLE);

        // The owner IP is now known.
        TextView view = (TextView) mContentView.findViewById(R.id.group_owner);
        view.setText(getResources().getString(R.string.group_owner_text)
                + ((info.isGroupOwner == true) ? getResources().getString(R.string.yes)
                        : getResources().getString(R.string.no)));

        // InetAddress from WifiP2pInfo struct.
        view = (TextView) mContentView.findViewById(R.id.device_info);
        view.setText("Group Owner IP - " + info.groupOwnerAddress.getHostAddress());


        // After the group negotiation, we assign the group owner as the file
        // server. The file server is single threaded, single connection server
        // socket.
        if (info.groupFormed && info.isGroupOwner) {
            // here wait for the group members to connect and get their corresponding IP addresses
            clientAddresses[0] = info.groupOwnerAddress.getHostAddress();

            new GetClientIPAddressAtServer(getActivity(), mContentView.findViewById(R.id.group_client),mContentView.findViewById(R.id.resultData))
                    .execute();

            /*new FileServerAsyncTask(getActivity(), mContentView.findViewById(R.id.status_text))
                    .execute();*/
        } else if (info.groupFormed) {
            // The other device acts as the client. In this case, we enable the
            // get file button.
            String groupOwnerIP = info.groupOwnerAddress.getHostAddress();
            mContentView.findViewById(R.id.btn_start_client).setVisibility(View.VISIBLE);
            ((TextView) mContentView.findViewById(R.id.status_text)).setText(getResources()
                    .getString(R.string.client_text));

            new GetClientIPAddressAtClient(getActivity(),mContentView.findViewById(R.id.group_client)).execute(groupOwnerIP);

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
     * Clears the UI fields after a disconnect or direct mode disable operation.
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
        mContentView.findViewById(R.id.btn_start_client).setVisibility(View.GONE);
        this.getView().setVisibility(View.GONE);
    }


    /*

    A simple Async Class to accept connection from clients and send back IP addresses to client.

     */
    private class GetClientIPAddressAtServer extends AsyncTask<Void,Void,String[]>{

        private Context context;
        private TextView resultText;
        private TextView clients;
        private int noOfClients = devices - 1;
        private int index = 0;
        private List<File> listOfFilesServer = new ArrayList<File>();


        public GetClientIPAddressAtServer(Context context,View clients,View resultData) {
            this.context = context;
            this.resultText = (TextView) resultData;
            this.clients = (TextView) clients;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            String clientIP = "0.0.0.0";
            String inputLine;

            try {
                ServerSocket serverSocket = new ServerSocket(mPortControl);
                serverSocket.setReuseAddress(true);

                // Multi Clients Server
                while(noOfClients > 0){
                        Socket client = serverSocket.accept();
                        PrintWriter out = new PrintWriter(client.getOutputStream(),true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        while((inputLine = in.readLine()) != null){
                            if(inputLine.equalsIgnoreCase("CLIENTIP")){
                                //Send the Client IP Address
                                out.println(client.getInetAddress().getHostAddress());
                                Log.d(WiFiDirectActivity.TAG,"Client IP Address "+client.getInetAddress().getHostAddress());
                                clientIP = client.getInetAddress().getHostAddress();
                                clientAddresses[noOfClients] = clientIP;
                                Log.e(WiFiDirectActivity.TAG,clientAddresses[noOfClients--]);
                            }else if(inputLine.equalsIgnoreCase("EXIT")){
                                break;
                            }
                        }

                        out.flush();
                        out.close();
                        in.close();
                        client.close();
                }

                serverSocket.close();


            } catch (IOException e) {
                e.printStackTrace();
            }

            //Broadcasting IP Addresses to the Clients
            noOfClients = devices - 1;
            String IP = null;

            while(noOfClients > 0){
                Socket server = new Socket();
                IP = clientAddresses[noOfClients];
                try {
                    server.connect((new InetSocketAddress(IP,mPortData)),SOCKET_TIMEOUT);
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(server.getOutputStream());
                    ObjectInputStream objectInputStream = new ObjectInputStream(server.getInputStream());
                    objectOutputStream.writeObject(new String("ALLCLIENTIP"));
                    if(objectInputStream.readObject().equals("SENDALLCLIENTIP")){
                        objectOutputStream.writeObject(clientAddresses);
                    }
                    objectOutputStream.flush();
                    objectInputStream.close();
                    objectOutputStream.close();
                    server.close();

                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
                noOfClients--;

            }

           // Receive the file parts from clients
            noOfClients = devices - 1;
            while(noOfClients > 0){
                try{
                    Socket serverSocket = new Socket();
                    serverSocket.connect(new InetSocketAddress(clientAddresses[noOfClients],mPortData),SOCKET_TIMEOUT);

                    InputStream inputstream = serverSocket.getInputStream();
                    DataInputStream dataIn = new DataInputStream(inputstream);
                    OutputStream outputStream = serverSocket.getOutputStream();
                    DataOutputStream dataOut = new DataOutputStream(outputStream);

                    dataOut.writeUTF("SENDDATA");
                    dataOut.flush();

                    index = (int)dataIn.readInt();
                    long size = dataIn.readLong();
                    String fileName = "D2DFile.receivedPart"+index;

                    File rFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Server/"+ fileName);
                    File dirs = new File(rFile.getParent());
                    if (!dirs.exists())
                        dirs.mkdirs();
                    rFile.createNewFile();
                    Log.d(WiFiDirectActivity.TAG, "server: copying files " + rFile.toString());

                    copyFile(dataIn, new FileOutputStream(rFile),size);

                    noOfClients--;

                    outputStream.close();
                    dataOut.close();
                    inputstream.close();
                    dataIn.close();
                    serverSocket.close();

                }catch (IOException e) {
                    e.printStackTrace();
                }
            }


            //Copy server file part from FILE folder to SERVER Folder
            String sfileName = "D2DFile.part0";
            String destfileName = "D2DFile.receivedPart0";
            File serverFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Files/" + sfileName );
            File destFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Server/"+ destfileName);
            try {
                copyFiletoAnotherFolder(serverFile,destFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

            //Send all files to all the clients
            for(int client = 1;client < devices;client++){
                Socket sendData = new Socket();
                try {

                    sendData.connect(new InetSocketAddress(clientAddresses[client],mPortData),SOCKET_TIMEOUT);

                    InputStream inputstream = sendData.getInputStream();
                    DataInputStream dataIn = new DataInputStream(inputstream);
                    OutputStream outputStream = sendData.getOutputStream();
                    DataOutputStream dataOut = new DataOutputStream(outputStream);

                    dataOut.writeUTF("SENDING");
                    dataOut.flush();
                    if(dataIn.readUTF().equals("YES")){
                        dataOut.writeInt(devices - 1); // No of parts
                        dataOut.flush();
                        for(int part = 0;part < devices;part++){
                            if(client != part){

                                //send the file part
                                String fileName = "D2DFile.part"+part;
                                File sFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Server/"+ fileName);
                                long size = sFile.length();
                                byte[] byteArray = new byte[(int) size];

                                FileInputStream fis = new FileInputStream(sFile);
                                BufferedInputStream bis = new BufferedInputStream(fis);
                                DataInputStream dis = new DataInputStream(bis);
                                dis.readFully(byteArray,0,byteArray.length);

                                if((inputLine = dataIn.readUTF()).equals("SENDDATA")){
                                    dataOut.writeInt(part);
                                    dataOut.writeLong(size);
                                    dataOut.flush();
                                    dataOut.write(byteArray,0,byteArray.length);
                                    dataOut.flush();
                                }

                            }
                        }
                    }

                    outputStream.flush();
                    outputStream.close();
                    dataOut.close();
                    inputstream.close();
                    dataIn.close();
                    sendData.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }


            }



            // Return string array of files shared
            File serverFolder = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Server");
            File[] listOfFiles = serverFolder.listFiles();
            int filenameIndex = 0;
            for(File i : listOfFiles){
                receiveData[filenameIndex++] = i.getName();
            }

            // Join all the file parts into single file


            //return receiveData;
            return clientAddresses;
        }

        @Override
        protected void onPostExecute(String[] s) {
            String textString = "";
            String resultString = "";
            for(int j = 0; j < devices;j++){
                textString = textString + clientAddresses[j] + " \n ";
                //resultString = resultString + s[j] + "\n";
            }
            clients.setText("Clients Connected IP \n" + textString);
            //resultText.setText("Files Shared \n " + resultString);
        }
    }

    private class GetClientIPAddressAtClient extends AsyncTask<String,Void,String[]>{
        private Context context;
        private TextView statusText;
        String[] allClientIP = new String[devices];
        private int index = 0;

        /**
         * @param context
         * @param statusText
         */
        public GetClientIPAddressAtClient(Context context, View statusText) {
            this.context = context;
            this.statusText = (TextView) statusText;

        }

        @Override
        protected String[] doInBackground(String... params) {
            String groupOwnerIP = params[0];
            String clientIP = "0.0.0.0";
            String inputLine, outputLine = "";

            Socket clientSocket = new Socket();
            try {
                clientSocket.setReuseAddress(true);
                clientSocket.connect((new InetSocketAddress(groupOwnerIP, mPortControl)), SOCKET_TIMEOUT);
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader((clientSocket.getInputStream())));
                out.println("CLIENTIP");
                while ((inputLine = in.readLine()) != null) {
                    clientIP = inputLine;
                    out.println("EXIT");
                    break;
                }
                out.flush();
                out.close();
                in.close();
                clientSocket.close();


            } catch (SocketException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            //Getting the IP Addresses of all the clients from the server
            ServerSocket socket = null;
            int index = 0;

            try {
                socket = new ServerSocket(mPortData);
                socket.setReuseAddress(true);
                Socket groupOwner = socket.accept();
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(groupOwner.getOutputStream());
                ObjectInputStream objectInputStream = new ObjectInputStream(groupOwner.getInputStream());
                if(objectInputStream.readObject().equals("ALLCLIENTIP")){
                    objectOutputStream.writeObject(new String("SENDALLCLIENTIP"));
                }
                String[] receiveClientIP = (String[]) objectInputStream.readObject();
                Log.e(WiFiDirectActivity.TAG, "Received the String Object ");
                for (int q = 0; q < devices; q++) {
                    Log.d(WiFiDirectActivity.TAG, receiveClientIP[q]);
                    allClientIP[q] = receiveClientIP[q];
                }
                objectOutputStream.flush();
                objectInputStream.close();
                objectOutputStream.close();
                groupOwner.close();
                socket.close();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }


            //Identify the index
            int i = 0;
            for (i = 1; i < devices; i++) {
                if (clientIP.equals((String) allClientIP[i])) {
                    index = i;
                    break;
                }
            }

            // Sending the file part to server
            try {
                ServerSocket dataSocket = new ServerSocket(mPortData);
                dataSocket.setReuseAddress(true);
                Socket server = dataSocket.accept();

                InputStream in = server.getInputStream();
                DataInputStream serverData = new DataInputStream(in);
                OutputStream out = server.getOutputStream();
                DataOutputStream clientData = new DataOutputStream(out);

                String fileName = "D2DFile.part"+index;
                File sFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Files/" + fileName);
                long size = sFile.length();
                byte[] byteArray = new byte[(int) size];

                FileInputStream fis = new FileInputStream(sFile);
                BufferedInputStream bis = new BufferedInputStream(fis);
                DataInputStream dis = new DataInputStream(bis);
                dis.readFully(byteArray,0,byteArray.length);

                if((inputLine = serverData.readUTF()).equals("SENDDATA")){
                    clientData.writeInt(index);
                    clientData.writeLong(size);
                    clientData.flush();
                    clientData.write(byteArray,0,byteArray.length);
                    clientData.flush();
                }

                out.close();
                clientData.close();
                in.close();
                serverData.close();
                dis.close();
                dataSocket.close();
                server.close();
            } catch (IOException e) {
                e.printStackTrace();
            }


            // Get all the file parts from server
            int rIndex = 0;
            int noOfParts = 0;
            try {
                ServerSocket receiveParts = new ServerSocket(mPortData);
                receiveParts.setReuseAddress(true);
                Socket getData = receiveParts.accept();

                InputStream inputstream = getData.getInputStream();
                DataInputStream dataIn = new DataInputStream(inputstream);
                OutputStream outputStream = getData.getOutputStream();
                DataOutputStream dataOut = new DataOutputStream(outputStream);

                if(dataIn.readUTF().equals("SENDING")){
                    dataOut.writeUTF("YES");
                    dataOut.flush();
                    noOfParts = dataIn.readInt(); //get no of parts

                    for(int parts = 0;parts < noOfParts;parts++){
                        dataOut.writeUTF("SENDDATA");
                        dataOut.flush();
                        rIndex =  dataIn.readInt(); //get the file part
                        long size = dataIn.readLong(); //get the file size
                        String fileName = "D2DFile.receivedPart"+rIndex;

                        File receivedFile = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/" + "/Client/"+fileName);
                        File dirs = new File(receivedFile.getParent());
                        if(!dirs.exists()){
                            dirs.mkdirs();
                        }
                        receivedFile.createNewFile();

                        copyFile(dataIn,new FileOutputStream(receivedFile),size);

                    }

                }

                outputStream.flush();
                outputStream.close();
                dataOut.flush();
                dataOut.close();
                dataIn.close();
                inputstream.close();
                getData.close();
                receiveParts.close();
            } catch (IOException e) {
                e.printStackTrace();
            }



            // Return string array of files shared
            File clientFolder = new File(Environment.getExternalStorageDirectory() + "/WifiNetworking/"+ "/Server");
            File[] listOfFiles = clientFolder.listFiles();
            int filenameIndex = 0;
            for(File q : listOfFiles){
                receiveData[filenameIndex++] = q.getName();
            }

            //join all the file parts



            return allClientIP;
        }

        @Override
        protected void onPostExecute(String[] s) {

            String textString = "";
            String resultString = "";

                for(int j = 0;j < devices;j++){
                    textString = textString + s[j] + "\n";
                    //resultString = resultString + receiveData[j] + "\n";
                }

                statusText.setText("Client IP Addresses : \n" + textString);

                /*TextView resultText = (TextView)mContentView.findViewById(R.id.resultData);
                resultText.setText("Files Shared \n "+ resultString);*/



        }
    }

    public static boolean copyFile(DataInputStream inputStream, OutputStream out,long size) {
        byte buf[] = new byte[1024];
        int bytesRead;
        int current = 0;
        try {
            while ((size > 0) && (bytesRead = inputStream.read(buf,0,(int)Math.min(buf.length,size))) != -1) {
                out.write(buf, 0, bytesRead);
                size -= bytesRead;
            }
            out.close();
            inputStream.close();
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
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            is.close();
            os.close();
        }

        return true;

    }


    public class MultiServerThread implements Runnable {

        private Socket socket = null;
        private int index = 0;
        private String clientIP = "0.0.0.0";
        public MultiServerThread(Socket soc,int index){
            this.socket = soc;
            this.index = index;
        }
        @Override
        public void run() {
            try {
                PrintWriter out = new PrintWriter(socket.getOutputStream(),true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String inputLine;
                while((inputLine = in.readLine()) != null){
                    if(inputLine.equalsIgnoreCase("CLIENTIP")){
                        out.println(socket.getInetAddress().getHostAddress());
                        Log.d(WiFiDirectActivity.TAG,"Client IP Address "+socket.getInetAddress().getHostAddress());
                        clientIP = socket.getInetAddress().getHostAddress();
                        clientAddresses[index] = clientIP;
                        Log.e(WiFiDirectActivity.TAG,clientAddresses[index]);
                    }else if(inputLine.equalsIgnoreCase("EXIT")){
                        break;
                    }
                }

                out.flush();
                out.close();
                in.close();
                socket.close();

            } catch (IOException e) {
                e.printStackTrace();
            }

        }

    }

}
