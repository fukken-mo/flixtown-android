package com.flixtown.tv;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileOpeningTest {
    @Test public void freshOpeningNeedsAChoiceEvenAfterEarlierSelection(){
        ProfileOpening p=new ProfileOpening();assertTrue(p.needsChoice("account"));
        p.chosen("account");assertFalse(p.needsChoice("account"));
        p.opened();assertTrue(p.needsChoice("account"));
    }
    @Test public void foregroundReturnAsksAgain(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");
        p.foreground(true);assertTrue(p.needsChoice("account"));
    }
    @Test public void internalPickerReturnDoesNotLoop(){
        ProfileOpening p=new ProfileOpening();int opening=p.generation();
        p.returningHome("account");p.foreground(true);p.homeResumed();
        assertFalse(p.needsChoice("account"));assertEquals(opening,p.generation());
        p.foreground(true);assertTrue(p.needsChoice("account"));
    }
    @Test public void noInternalGapDoesNotSuppressTheNextRealOpening(){
        ProfileOpening p=new ProfileOpening();p.returningHome("account");
        p.homeResumed();p.foreground(true);assertTrue(p.needsChoice("account"));
    }
    @Test public void backgroundReturnToPlayerWaitsForHomeChoice(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");p.foreground(false);
        assertTrue(p.needsChoice("account"));p.chosen("account");assertFalse(p.needsChoice("account"));
    }
    @Test public void returningFromDetailsDoesNotStartAnotherOpening(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");
        p.homeResumed();assertFalse(p.needsChoice("account"));
    }
    @Test public void scopesAreIndependentAndNoAccountDoesNotPrompt(){
        ProfileOpening p=new ProfileOpening();p.chosen("A");assertTrue(p.needsChoice("B"));
        assertFalse(p.needsChoice(""));assertFalse(p.needsChoice(null));
    }
    @Test public void trailerReturnKeepsTheSelectedProfileAndOpening(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");int generation=p.generation();
        p.externalLaunched("movie-details");p.foreground(false,"movie-details");
        p.externalResumed("movie-details");p.homeResumed();
        assertFalse(p.needsChoice("account"));assertEquals(generation,p.generation());
        p.foreground(true);assertTrue(p.needsChoice("account"));
    }
    @Test public void trailerReturnIsConsumedOnlyOnce(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");p.externalLaunched("details");
        p.foreground(false,"details");p.foreground(false,"details");assertTrue(p.needsChoice("account"));
    }
    @Test public void anotherScreenDoesNotConsumeATrailerExemption(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");p.externalLaunched("movie-A");
        p.foreground(false,"movie-B");assertTrue(p.needsChoice("account"));
        p.chosen("account");p.foreground(false,"movie-A");assertTrue(p.needsChoice("account"));
    }
    @Test public void cancelledLaunchOrChooserWithoutBackgroundDoesNotSuppressReopening(){
        ProfileOpening p=new ProfileOpening();p.chosen("account");p.externalLaunched("details");
        p.externalResumed("details");p.foreground(false,"details");assertTrue(p.needsChoice("account"));
    }
    @Test public void newOpeningClearsAnyPendingTrailerReturn(){
        ProfileOpening p=new ProfileOpening();p.externalLaunched("details");p.opened();p.chosen("account");
        p.foreground(false,"details");assertTrue(p.needsChoice("account"));
    }
}
