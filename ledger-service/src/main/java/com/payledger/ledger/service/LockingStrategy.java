package com.payledger.ledger.service;

/** Which transfer implementation serves requests (ADR 0009). */
public enum LockingStrategy { PESSIMISTIC, OPTIMISTIC }
