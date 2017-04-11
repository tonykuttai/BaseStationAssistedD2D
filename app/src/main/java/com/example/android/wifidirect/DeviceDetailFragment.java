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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.example.android.wifidirect.DeviceListFragment.DeviceActionListener;

import java.io.BufferedReader;
import java.io.File;
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

    /**
     * A simple server socket that accepts connection and writes some data on
     * the stream.
     */
    public static class FileServerAsyncTask extends AsyncTask<Void, Void, String> {

        private Context context;
        private TextView statusText;

        /**
         * @param context
         * @param statusText
         */
        public FileServerAsyncTask(Context context, View statusText) {
            this.context = context;
            this.statusText = (TextView) statusText;
        }

        @Override
        protected String doInBackground(Void... params) {
            try {
                ServerSocket serverSocket = new ServerSocket(mPortData);
                Log.d(WiFiDirectActivity.TAG, "Server: Socket opened");
                Socket client = serverSocket.accept();
                Log.d(WiFiDirectActivity.TAG, "Server: connection done");
                final File f = new File(Environment.getExternalStorageDirectory() + "/"
                        + context.getPackageName() + "/wifip2pshared-" + System.currentTimeMillis()
                        + ".jpg");

                File dirs = new File(f.getParent());
                if (!dirs.exists())
                    dirs.mkdirs();
                f.createNewFile();

                Log.d(WiFiDirectActivity.TAG, "server: copying files " + f.toString());
                InputStream inputstream = client.getInputStream();
                copyFile(inputstream, new FileOutputStream(f));
                serverSocket.close();
                return f.getAbsolutePath();
            } catch (IOException e) {
                Log.e(WiFiDirectActivity.TAG, e.getMessage());
                return null;
            }
        }

        /*
         * (non-Javadoc)
         * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
         */
        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                statusText.setText("File copied - " + result);
                Intent intent = new Intent();
                intent.setAction(android.content.Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse("file://" + result), "image/*");
                context.startActivity(intent);
            }

        }

        /*
         * (non-Javadoc)
         * @see android.os.AsyncTask#onPreExecute()
         */
        @Override
        protected void onPreExecute() {
            statusText.setText("Opening a server socket");
        }

    }

    /*

    A simple Async Class to accept connection from clients and send back IP addresses to client.

     */
    private class GetClientIPAddressAtServer extends AsyncTask<Void,Void,String[]>{

        private Context context;
        private TextView resultText;
        private TextView clients;
        private int noOfClients = 2;
        private int index = 0;


        public GetClientIPAddressAtServer(Context context,View clients,View resultData) {
            this.context = context;
            this.resultText = (TextView) resultData;
            this.clients = (TextView) clients;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            String clientIP = "0.0.0.0";
            String inputLine;
            receiveData[index] = sampleData[index];
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
            noOfClients = 2;
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

           // Receive the string parts from clients

            noOfClients = 2;
            while(noOfClients > 0){
                try{
                    Socket serverSocket = new Socket();
                    serverSocket.connect(new InetSocketAddress(clientAddresses[noOfClients],mPortData),SOCKET_TIMEOUT);
                    ObjectInputStream objectInputStream = new ObjectInputStream(serverSocket.getInputStream());
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(serverSocket.getOutputStream());

                    objectOutputStream.writeObject("SENDDATA");
                    index = (int)objectInputStream.readObject();
                    String data = (String) objectInputStream.readObject();
                    noOfClients--;
                    receiveData[index] = data;
                    objectOutputStream.flush();
                    objectInputStream.close();
                    objectOutputStream.close();
                    serverSocket.close();

                }catch (IOException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }


            //Send Data to all the clients

            for(int client = 1;client < 3;client++){
                Socket sendData = new Socket();
                try {
                    sendData.connect(new InetSocketAddress(clientAddresses[client],mPortData),SOCKET_TIMEOUT);
                    ObjectInputStream objectInputStream = new ObjectInputStream(sendData.getInputStream());
                    ObjectOutputStream objectOutputStream = new ObjectOutputStream(sendData.getOutputStream());
                    objectOutputStream.writeObject("SENDING");
                    if(objectInputStream.readObject().equals("YES")){
                        for(int device = 0;device < 3;device++){
                            if(client != device){
                                objectOutputStream.writeObject(device);
                                objectOutputStream.writeObject(receiveData[device]);
                            }
                        }
                    }

                    objectOutputStream.flush();
                    objectOutputStream.close();
                    objectInputStream.close();
                    sendData.close();

                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }

            }

            return receiveData;
        }

        @Override
        protected void onPostExecute(String[] s) {
            clients.setText("Clients Connected IP \n" + clientAddresses[0] +"\n"+clientAddresses[1]+"\n"+clientAddresses[2]);
            resultText.setText("Result Data : "+s[0]+" "+s[1]+" "+s[2]);
        }
    }

    private class GetClientIPAddressAtClient extends AsyncTask<String,Void,String[]>{
        private Context context;
        private TextView statusText;
        //private TextView resultText;
        //String[] allClientIP;
        private int index = 0;

        /**
         * @param context
         * @param statusText
         */
        public GetClientIPAddressAtClient(Context context, View statusText) {
            this.context = context;
            this.statusText = (TextView) statusText;
            //this.resultText = (TextView) resultText;
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
            String[] allClientIP = new String[3];
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
                for (int q = 0; q < 3; q++) {
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


            // Send the String Part to the Server
            int i = 0;
            for (i = 1; i < 3; i++) {
                if (clientIP.equals((String) allClientIP[i])) {
                    receiveData[i] = sampleData[i];
                    index = i;
                    break;
                }
            }

            Log.e(WiFiDirectActivity.TAG,"Sending the part : index "+ index + " : String : " + receiveData[index]);

            try {
                ServerSocket dataSocket = new ServerSocket(mPortData);
                dataSocket.setReuseAddress(true);
                Socket server = dataSocket.accept();
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(server.getOutputStream());
                ObjectInputStream objectInputStream = new ObjectInputStream(server.getInputStream());

                if(objectInputStream.readObject().equals("SENDDATA")){
                    objectOutputStream.writeObject(index);
                    objectOutputStream.writeObject(receiveData[index]);
                }
                objectOutputStream.flush();
                objectOutputStream.close();
                objectInputStream.close();
                dataSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }


            int rIndex = 0;
            try {
                ServerSocket receiveParts = new ServerSocket(mPortData);
                receiveParts.setReuseAddress(true);
                Socket getData = receiveParts.accept();
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(getData.getOutputStream());
                ObjectInputStream objectInputStream = new ObjectInputStream(getData.getInputStream());

                if(objectInputStream.readObject().equals("SENDING")){
                    objectOutputStream.writeObject("YES");
                    for(int parts = 0;parts < 2;parts++){
                        rIndex = (int)objectInputStream.readObject();
                        String data = (String) objectInputStream.readObject();
                        receiveData[rIndex] = data;
                    }
                }
                objectOutputStream.flush();
                objectInputStream.close();
                objectOutputStream.close();
                receiveParts.close();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }




            return allClientIP;
        }

        @Override
        protected void onPostExecute(String[] s) {

            if (s != null) {
                statusText.setText("Client IP Addresses " + s[0] +"\n" +s[1] +"\n" +s[2]);
            }

            TextView resultText = (TextView)mContentView.findViewById(R.id.resultData);
            resultText.setText("Result Data "+ receiveData[0] + " " + receiveData[1] +" " + receiveData[2]);


        }
    }

    public static boolean copyFile(InputStream inputStream, OutputStream out) {
        byte buf[] = new byte[1024];
        int len;
        try {
            while ((len = inputStream.read(buf)) != -1) {
                out.write(buf, 0, len);

            }
            out.close();
            inputStream.close();
        } catch (IOException e) {
            Log.d(WiFiDirectActivity.TAG, e.toString());
            return false;
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
