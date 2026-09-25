#version 300 es
precision highp float;
in vec3 vPosition, vNormal, vLocal;
layout(location=0) out vec4 fragColor;
uniform sampler2D uBackdrop;
uniform vec2 uViewport;
uniform vec3 uBase, uAccent, uAbsorption, uContact, uEmission;
uniform vec4 uOptics; // roughness, transmission, IOR, thickness
uniform vec4 uFinish; // iridescence, cloud, clearcoat, attenuation distance
uniform float uTime, uPressure, uSelected, uEnabled, uReveal;

float opHash(vec3 p) {
  p = fract(p * 0.1031); p += dot(p, p.yzx + 33.33);
  return fract((p.x + p.y) * p.z);
}
float opNoise(vec3 p) {
  vec3 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(mix(opHash(i),opHash(i+vec3(1,0,0)),f.x),
                 mix(opHash(i+vec3(0,1,0)),opHash(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(opHash(i+vec3(0,0,1)),opHash(i+vec3(1,0,1)),f.x),
                 mix(opHash(i+vec3(0,1,1)),opHash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
float opFbm(vec3 p) {
  float f=0.0,a=0.5;
  mat3 r=mat3(0.00,0.80,0.60,-0.80,0.36,-0.48,-0.60,-0.48,0.64);
  for(int i=0;i<5;i++){f+=a*opNoise(p);p=r*p*2.03+vec3(4.7,1.3,7.9);a*=0.5;}
  return f;
}
vec3 opFlow(vec3 p,float t) {
  // Analytic divergence-free cyclic field. This advects visual density coordinates,
  // not conserved pigment: simulation-backed pigment must supply its own density texture.
  return vec3(sin(p.y*1.13+t*.17)+cos(p.z*.97-t*.13),
              sin(p.z*1.07+t*.11)+cos(p.x*1.21+t*.09),
              sin(p.x*.91-t*.15)+cos(p.y*1.03+t*.12));
}

const float PI=3.14159265359;
vec3 environment(vec3 r,float roughness) {
    vec3 sky=mix(vec3(.055,.095,.14),vec3(.50,.68,.84),smoothstep(-.3,.9,r.y));
    float key=exp(-pow((r.x+.45)/(roughness*.5+.20),2.)-pow((r.y-.70)/(.06+roughness*.3),2.));
    float strip=exp(-pow((r.x-.72)/(.025+roughness*.22),2.)-pow((r.y-.15)/.7,2.));
    return sky+vec3(1.8,1.9,2.0)*key+vec3(.55,.95,1.35)*strip;
}
vec3 light(vec3 n,vec3 v,vec3 l,vec3 color,float rough,float f0) {
    vec3 h=normalize(v+l);
    float nl=max(dot(n,l),0.0),nv=max(dot(n,v),.001),nh=max(dot(n,h),0.0),vh=max(dot(v,h),0.0);
    float a=rough*rough,a2=a*a;
    float d=a2/(PI*pow(nh*nh*(a2-1.)+1.,2.)+.00001);
    float k=pow(rough+1.,2.)/8.;
    float g=nv/(nv*(1.-k)+k)*nl/(nl*(1.-k)+k);
    float f=f0+(1.-f0)*pow(1.-vh,5.);
    return color*(d*g*f/(4.*nv*max(nl,.001)))*nl;
}
vec3 aces(vec3 x) { return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.,1.); }
void main() {
    vec3 n=normalize(vNormal),v=normalize(-vPosition);
    if(!gl_FrontFacing)n=-n;
    float nv=max(dot(n,v),.02);
    float rough=clamp(uOptics.x+(opNoise(vLocal*42.)-.5)*.025,.045,.9);
    float eta=uOptics.z,f0=pow((eta-1.)/(eta+1.),2.);
    float fresnel=f0+(1.-f0)*pow(1.-nv,5.);
    vec3 advected=vLocal*2.4+opFlow(vLocal*2.4,uTime)*.075;
    float cloud=opFbm(advected+opFbm(advected*.7)*1.6);
    vec3 base=mix(uBase,uBase*mix(vec3(.10,.30,.44),uAccent,smoothstep(.42,.68,cloud))*1.55,uFinish.y);
    // Refraction samples a separate scene receiver, never its own attachment.
    vec3 ray=refract(-v,n,1./eta);
    float path=uOptics.w/max(.24,nv);
    vec2 uv=gl_FragCoord.xy/uViewport;
    vec2 bend=ray.xy*path*.018;
    vec3 transmitted=pow(texture(uBackdrop,clamp(uv+bend,vec2(.001),vec2(.999))).rgb,vec3(2.2));
    vec3 attenuation=exp(log(max(uAbsorption,vec3(.015)))*path/max(.1,uFinish.w));
    transmitted=transmitted*attenuation + base*(1.-attenuation)*.65;
    float diffuse=max(dot(n,normalize(vec3(-.45,.8,.5))),0.);
    vec3 body=base*(.19+diffuse*.65);
    vec3 reflected=environment(reflect(-v,n),rough);
    vec3 rgb=mix(body,transmitted,uOptics.y)*(1.-fresnel)+reflected*fresnel;
    rgb+=light(n,v,normalize(vec3(-.45,.8,.5)),vec3(2.1,2.05,1.9),rough,f0);
    rgb+=light(n,v,normalize(vec3(.7,.1,.7)),vec3(.6,.85,1.2),rough,f0)*.55;
    rgb+=light(n,v,normalize(vec3(-.45,.8,.5)),vec3(1.2),.07,.04)*uFinish.z*.4;
    float film=(1.-nv)*uFinish.x;
    vec3 interference=.5+.5*cos(vec3(0.,2.094,4.188)+(nv*5.+cloud*.8)*6.283);
    rgb+=interference*film*.35;
    vec3 d=vLocal-uContact;
    float contact=exp(-dot(d,d)/.1764);
    rgb+=uAccent*(uPressure*contact*.42+uSelected*.075)+uEmission;
    float sweep=exp(-pow((vLocal.x-(uReveal-.72)*8.)*4.,2.))*step(.72,uReveal)*(1.-step(.999,uReveal));
    rgb+=uAccent*sweep*.15;
    rgb=mix(rgb*.48,rgb,uEnabled);
    fragColor=vec4(pow(aces(rgb),vec3(1./2.2)),1.);
}
