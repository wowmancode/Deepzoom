// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Runs the real live-view shaders on a GLES 3.1 driver (Mesa llvmpipe works, no GPU
// needed) and checks the data path: escape data written to RGBA8 and coloured by the
// present pass must match the direct-colour output; the editor preview box must be an
// exact centre crop; coarse frames and diagnostics must still present. See run.sh.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
static char* rd(const char* p, long* n){FILE*f=fopen(p,"rb");fseek(f,0,2);long l=ftell(f);rewind(f);char*b=malloc(l+1);fread(b,1,l,f);b[l]=0;fclose(f);if(n)*n=l;return b;}
static GLuint sh(GLenum t,const char*s){GLuint h=glCreateShader(t);glShaderSource(h,1,&s,0);glCompileShader(h);GLint ok;glGetShaderiv(h,GL_COMPILE_STATUS,&ok);if(!ok){char l[4096];glGetShaderInfoLog(h,4096,0,l);printf("compile: %s\n",l);exit(1);}return h;}
static GLuint prog(const char*v,const char*f){GLuint p=glCreateProgram();glAttachShader(p,sh(GL_VERTEX_SHADER,v));glAttachShader(p,sh(GL_FRAGMENT_SHADER,f));glLinkProgram(p);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok){char l[4096];glGetProgramInfoLog(p,4096,0,l);printf("link: %s\n",l);exit(1);}return p;}
static GLuint tex(int w,int h,const void*d){GLuint t;glGenTextures(1,&t);glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,d);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);return t;}
static GLuint fbo(GLuint t){GLuint f;glGenFramebuffers(1,&f);glBindFramebuffer(GL_FRAMEBUFFER,f);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,t,0);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){printf("fbo incomplete\n");exit(1);}return f;}
#define W 320
#define H 240
GLuint dp, bp, rampT, tileT;
void direct(GLuint target,int w,int h,int dataOut,int dbg){
  glBindFramebuffer(GL_FRAMEBUFFER,target);glViewport(0,0,w,h);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
  glUseProgram(dp);
  glUniform2f(glGetUniformLocation(dp,"uResolution"),w,h);
  glUniform1i(glGetUniformLocation(dp,"uMaxIter"),1000);
  glUniform1f(glGetUniformLocation(dp,"uCycle"),64);glUniform1f(glGetUniformLocation(dp,"uOffset"),0.1f);
  glUniform3f(glGetUniformLocation(dp,"uInterior"),0.1f,0.2f,0.3f);
  glUniform1i(glGetUniformLocation(dp,"uDebug"),dbg);glUniform1i(glGetUniformLocation(dp,"uDataOut"),dataOut);
  glUniform1i(glGetUniformLocation(dp,"uUseTiles"),0);glUniform1i(glGetUniformLocation(dp,"uTileSize"),32);
  glUniform2f(glGetUniformLocation(dp,"uCenter"),-0.745f,0.11f);glUniform1f(glGetUniformLocation(dp,"uSpanY"),0.02f);
  glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,rampT);glUniform1i(glGetUniformLocation(dp,"uPalette"),3);
  glActiveTexture(GL_TEXTURE5);glBindTexture(GL_TEXTURE_2D,tileT);glUniform1i(glGetUniformLocation(dp,"uTiles"),5);
  glDrawArrays(GL_TRIANGLES,0,3);
}
void blit(GLuint target,GLuint scene,int vx,int vy,int vw,int vh,float fx,float fy,float valW,float valH,float zoom,float sx,int dbg){
  glBindFramebuffer(GL_FRAMEBUFFER,target);glViewport(vx,vy,vw,vh);
  glUseProgram(bp);
  glUniform2f(glGetUniformLocation(bp,"uResolution"),vw,vh);glUniform2f(glGetUniformLocation(bp,"uOrigin"),vx,vy);
  glUniform2f(glGetUniformLocation(bp,"uFit"),fx,fy);glUniform2f(glGetUniformLocation(bp,"uValidSize"),valW,valH);
  glUniform2f(glGetUniformLocation(bp,"uShift"),sx,0);glUniform1f(glGetUniformLocation(bp,"uZoom"),zoom);
  glUniform3f(glGetUniformLocation(bp,"uBackground"),0.1f,0.2f,0.3f);
  glUniform1f(glGetUniformLocation(bp,"uCycle"),64);glUniform1f(glGetUniformLocation(bp,"uOffset"),0.1f);
  glUniform3f(glGetUniformLocation(bp,"uInterior"),0.1f,0.2f,0.3f);glUniform1i(glGetUniformLocation(bp,"uDebug"),dbg);
  glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_2D,scene);glUniform1i(glGetUniformLocation(bp,"uScene"),4);
  glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,rampT);glUniform1i(glGetUniformLocation(bp,"uPalette"),3);
  glDrawArrays(GL_TRIANGLES,0,3);
}
void readp(GLuint f,unsigned char*o){glBindFramebuffer(GL_FRAMEBUFFER,f);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,o);}
void ppm(const char*n,unsigned char*p){FILE*f=fopen(n,"wb");fprintf(f,"P6 %d %d 255\n",W,H);for(int y=H-1;y>=0;y--)for(int x=0;x<W;x++)fwrite(p+4*(y*W+x),1,3,f);fclose(f);}
int main(){
  EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);eglInitialize(d,0,0);eglBindAPI(EGL_OPENGL_ES_API);
  EGLint ca[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_NONE};EGLConfig c;EGLint nc;eglChooseConfig(d,ca,&c,1,&nc);
  EGLint cx[]={EGL_CONTEXT_MAJOR_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};EGLContext ctx=eglCreateContext(d,c,EGL_NO_CONTEXT,cx);
  EGLint pa[]={EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE};EGLSurface s=eglCreatePbufferSurface(d,c,pa);
  if(!eglMakeCurrent(d,s,s,ctx)){printf("no ctx\n");return 1;}
  printf("%s | %s\n",glGetString(GL_RENDERER),glGetString(GL_VERSION));
  glDisable(GL_DITHER);
  GLuint vao;glGenVertexArrays(1,&vao);glBindVertexArray(vao);
  char*v=rd("vert.glsl",0);dp=prog(v,rd("direct.glsl",0));bp=prog(v,rd("blit.glsl",0));
  long n;char*ramp=rd("ramp.bin",&n);
  glGenTextures(1,&rampT);glBindTexture(GL_TEXTURE_2D,rampT);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1024,1,0,GL_RGBA,GL_UNSIGNED_BYTE,ramp);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
  unsigned char z=0; glGenTextures(1,&tileT);glBindTexture(GL_TEXTURE_2D,tileT);glTexImage2D(GL_TEXTURE_2D,0,GL_R8,1,1,0,GL_RED,GL_UNSIGNED_BYTE,&z);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
  GLuint tA=tex(W,H,0),fA=fbo(tA),tD=tex(W,H,0),fD=fbo(tD),tB=tex(W,H,0),fB=fbo(tB),tC=tex(W,H,0),fC=fbo(tC);
  unsigned char*A=malloc(W*H*4),*B=malloc(W*H*4),*Dd=malloc(W*H*4),*C=malloc(W*H*4);
  int fails=0;
  // 1. colour mode vs data mode + present
  direct(fA,W,H,0,0);readp(fA,A);
  direct(fD,W,H,1,0);readp(fD,Dd);
  blit(fB,tD,0,0,W,H,1,1,W,H,1,0,0);readp(fB,B);
  int diff=0,maxd=0,interior=0,escaped=0;
  for(int i=0;i<W*H;i++){for(int k=0;k<3;k++){int dd=abs(A[4*i+k]-B[4*i+k]);if(dd>maxd)maxd=dd;if(dd>1){diff++;break;}}}
  for(int i=0;i<W*H;i++){unsigned b=((unsigned)Dd[4*i]<<24)|(Dd[4*i+1]<<16)|(Dd[4*i+2]<<8)|Dd[4*i+3];
    if((b&0xFFFFFF00u)==0x7FC00000u)interior++;else{float fv;memcpy(&fv,&b,4);if(isfinite(fv))escaped++;}}
  printf("data vs colour: %d/%d pixels differ by >1, max channel diff %d | escaped %d, interior codes %d, other %d\n",diff,W*H,maxd,escaped,interior,W*H-escaped-interior);
  if(diff>0||escaped+interior!=W*H||interior==0||escaped==0)fails++;
  ppm("colour.ppm",A);ppm("data_present.ppm",B);
  // 2. preview box: top 40%, centre crop at 1:1, should equal centre rows of B
  int bh=(int)(H*0.4);glBindFramebuffer(GL_FRAMEBUFFER,fC);glViewport(0,0,W,H);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
  blit(fC,tD,0,H-bh,W,bh,1.0f,(float)bh/H,W,H,1,0,0);readp(fC,C);
  int bd=0;int off=H/2-bh/2;
  for(int j=0;j<bh;j++)for(int x=0;x<W;x++)for(int k=0;k<3;k++){if(abs(C[4*((H-bh+j)*W+x)+k]-B[4*((off+j)*W+x)+k])>1){bd++;goto nx;}} nx:;
  int below=0;for(int i=0;i<(H-bh)*W;i++)if(C[4*i]|C[4*i+1]|C[4*i+2])below++;
  printf("preview box: %d mismatching rows-pixels vs centre crop; %d non-black pixels outside box\n",bd,below);
  if(bd>0||below>0)fails++;
  ppm("preview.ppm",C);
  // 3. coarse half-res data upscaled
  direct(fD,W/2,H/2,1,0);blit(fB,tD,0,0,W,H,1,1,W/2,H/2,1,0,0);readp(fB,B);
  long sum=0;for(int i=0;i<W*H;i++)for(int k=0;k<3;k++)sum+=abs(A[4*i+k]-B[4*i+k]);
  printf("half-res present: mean abs channel diff vs native %.1f\n",(double)sum/(W*H*3));ppm("half.ppm",B);
  // 4. debug colours: interior shows blue/red reasons
  direct(fD,W,H,1,1);blit(fB,tD,0,0,W,H,1,1,W,H,1,0,1);readp(fB,B);
  int blue=0,red=0;for(int i=0;i<W*H;i++){if(B[4*i]==0&&B[4*i+2]==255)blue++;if(B[4*i]==255&&B[4*i+1]==0&&B[4*i+2]==0)red++;}
  printf("debug: %d blue (out of iterations), %d red (cycle)\n",blue,red);
  printf(fails?"FAIL\n":"all ok\n");return fails;
}
