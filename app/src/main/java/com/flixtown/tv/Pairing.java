package com.flixtown.tv;

/**
 * What the TV does with one answer from the panel's pair-poll (pure Java, unit-tested).
 * The panel keeps a code for 10 minutes and hands the approved account out only once; after that
 * (or for an unknown code) it answers 404, or a status other than pending/approved.
 */
final class Pairing {
    private Pairing(){}
    static final int WAIT=0,APPROVED=1,NEW_CODE=2;
    /** A normal answer: pending keeps waiting, approved signs in, anything else needs a fresh code. */
    static int next(String status){
        if("approved".equals(status))return APPROVED;
        if("pending".equals(status))return WAIT;
        return NEW_CODE;
    }
    /** An HTTP error answer: an expired, used or unknown code (400/404/410) needs a fresh code; others are retried. */
    static int afterError(int httpStatus){return httpStatus==400||httpStatus==404||httpStatus==410?NEW_CODE:WAIT;}
}
