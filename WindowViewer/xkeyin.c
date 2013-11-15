// Barely tweaked version of https://sites.google.com/site/dannychouinard/Home/unix-linux-trinkets/little-utilities/xkeyin-send-x11-keyboard-events-to-an-x-window
// This is probably copyright violation, yada yada...

#include <X11/extensions/XTest.h>

#define XK_MISCELLANY 1
#define XK_PUBLISHING 1
#define XK_LATIN1 1
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <X11/Xlib.h>
#include <X11/keysymdef.h>

int main(argc,argv)
int argc; char *argv[]; {
  Display  *display;
  Window focusid;
  int shift,currentshift;
  int b,opt;
  char *windowid, *text;
  char s[80];
  KeySym ks;
  KeyCode kc;
  KeyCode shiftkc;
  windowid=text=NULL;
  currentshift=0;
  while((opt=getopt(argc,argv,"w:t:"))!=-1) {
    switch(opt) {
      case 'w' : windowid=optarg; break;
      case 't' : text=optarg; break;
    }
  }
  if ((display=XOpenDisplay(NULL)) == NULL) {
      (void) fprintf(stderr, "Can't connect to display\n");
      exit(-1);
  }
  if(windowid!=NULL) {
    if(windowid[0]!='0' || windowid[1]!='x') {
      fprintf(stderr,"Windowid must begin with \"0x\".\n");
      exit(1);
    }
    sscanf(windowid,"0x%x",(unsigned int *)&focusid);
    // XWarpPointer(display,None,focusid,0,0,0,0,0,0);
    // XFlush(display);
    XSetInputFocus(display,focusid,RevertToNone,CurrentTime);
    XGrabKeyboard(display,focusid,False,GrabModeSync,GrabModeSync,CurrentTime);
    XFlush(display);
  }

  XTestFakeKeyEvent(display,XKeysymToKeycode(display,XK_Control_L),True,0);
  XTestFakeKeyEvent(display,XKeysymToKeycode(display,XK_3),True,0);
  usleep(5000);
  XTestFakeKeyEvent(display,XKeysymToKeycode(display, XK_3),False,0);
  XTestFakeKeyEvent(display,XKeysymToKeycode(display,XK_Control_L),False,0);
  usleep(5000);
  XFlush(display);
  usleep(500000);

  if (text[0] == '1')
    ks = XK_Left;
  else if (text[0] == '2')
    ks = XK_Right;
  else if (text[0] == '3')
    ks = XK_minus;
  else if (text[0] == '4')
    ks = XK_plus;
  else if (text[0] == '5')
    ks = XK_space;
  else
    ks = XK_minus; // ??????

  XTestFakeKeyEvent(display,XKeysymToKeycode(display,ks),True,0);
  usleep(5000);
  XTestFakeKeyEvent(display,XKeysymToKeycode(display,ks),False,0);
  usleep(5000);
  XFlush(display);

  return(0);
}
