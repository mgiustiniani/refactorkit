package com.example.client;

import com.example.ApiService;

public class Client {
    ApiService service = new ApiService();
    String result = service.greet("world");
}
