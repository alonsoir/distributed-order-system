package com.example.order.events;

public record EventWrapper<T>(String type, T payload) {}
