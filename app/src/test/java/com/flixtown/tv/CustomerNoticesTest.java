package com.flixtown.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class CustomerNoticesTest {
 @Test public void approvalAndAvailabilityAreDifferentEvents(){assertEquals("approved",CustomerNotices.event("approved"));assertEquals("ready",CustomerNotices.event("local"));assertEquals("",CustomerNotices.event("available"));}
 @Test public void partialOrPendingNeverMeansReady(){for(String s:new String[]{"pending","partial","declined","requestable",""})assertEquals("",CustomerNotices.event(s));}
 @Test public void noticeHistoryIsPersonal(){assertTrue(SignOut.isPersonalKey("notice_seen_report:123"));assertTrue(SignOut.isPersonalKey("notice_seen_request:movie:1:ready"));assertFalse(SignOut.isPersonalKey("subtitle_size"));}
}
