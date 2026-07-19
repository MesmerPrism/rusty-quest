// Flat WebGL preview. Pattern math is derived with permission from Trevor
// Hewitt's vr_strobe at commit 52c71cc069f4102bc4148e05c5fd3fc4d5466479,
// then aligned to the bounded one-sample Quest shader. AGPL-3.0-or-later.

const vertexSource = `#version 300 es
in vec2 a_position;
out vec2 v_uv;
void main() { v_uv = a_position; gl_Position = vec4(a_position, 0.0, 1.0); }`;

const fragmentSource = `#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform vec2 u_resolution;
uniform float u_time;
uniform int u_kind;
uniform int u_colorCount;
uniform vec3 u_color1, u_color2, u_color3, u_fixationColor;
uniform float u_oscActive, u_oscFreq, u_oscShape;
uniform float u_scale, u_shearX, u_shearY, u_offsetX, u_offsetY;
uniform float u_shakeAmp, u_shakeFreq, u_rotSpeed, u_stepFactor;
uniform float u_trail, u_blur, u_glow, u_brightness, u_contrast;
uniform float u_noiseFreq, u_noiseStrength, u_noiseBias;
uniform float u_vigCenter, u_vigEdge, u_vigBias;
uniform float u_frequency, u_duty, u_noiseType, u_noiseResolution;
uniform float u_noisePhase1, u_noiseAmp1, u_noisePhase2, u_noiseAmp2;
uniform float u_fixationEnabled, u_fixationSize;

struct Pattern {
  float enabled, strength, period, speed;
  vec2 pivot;
  float distortFreq, distortAmp, distortSpeed, distPar, distOrth;
  float waveFreq, waveAmp, waveShape, angle;
  vec2 rotationPivot;
  float rotationSpeed, extent, noiseMove, perlinScale, perlinZSpeed, perlinZOffset;
};
uniform Pattern u_stripes[8];
uniform Pattern u_ripples[8];
uniform Pattern u_rays[8];
uniform Pattern u_perlins[8];
const float TAU = 6.28318530718;

float hash31(vec3 p) {
  p = fract(p * 0.3183099 + 0.1); p *= 17.0;
  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}
float noise3D(vec3 p) {
  vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(mix(hash31(i), hash31(i+vec3(1,0,0)), f.x),
                 mix(hash31(i+vec3(0,1,0)), hash31(i+vec3(1,1,0)), f.x), f.y),
             mix(mix(hash31(i+vec3(0,0,1)), hash31(i+vec3(1,0,1)), f.x),
                 mix(hash31(i+vec3(0,1,1)), hash31(i+vec3(1,1,1)), f.x), f.y), f.z);
}
vec2 rotatePoint(vec2 p, float a, vec2 pivot) {
  float s=sin(a), c=cos(a); p-=pivot;
  return vec2(p.x*c-p.y*s,p.x*s+p.y*c)+pivot;
}
vec2 movingOffset(float seed, float amount) {
  return vec2(sin(u_time*.5+seed)*cos(u_time*.3+seed*2.0),
              cos(u_time*.4+seed*3.0)*sin(u_time*.6+seed*1.5))*amount;
}
float shapedSine(float value) {
  float sine=sin(value), width=max(fwidth(sine),.001);
  float square=smoothstep(-width,width,sine)*2.0-1.0;
  float band=min(1.0,TAU/max(fwidth(value),.001));
  return mix(sine,square,u_stepFactor)*band;
}
float signalAt(vec2 rawUv) {
  vec2 shake=vec2(sin(u_time*u_shakeFreq),cos(u_time*u_shakeFreq*1.3))*u_shakeAmp;
  vec2 uv=rawUv+vec2(u_offsetX,u_offsetY)+shake;
  uv.x+=uv.y*u_shearX; uv.y+=uv.x*u_shearY; uv*=u_scale;
  uv=rotatePoint(uv,u_time*u_rotSpeed,vec2(0));
  float signal=0.0;
  for(int i=0;i<8;i++) {
    Pattern q=u_stripes[i]; if(q.enabled<.5) continue;
    vec2 p=rotatePoint(uv,-(q.angle+u_time*q.rotationSpeed),q.pivot)-q.pivot;
    if(q.distortAmp>0.0) p+=(noise3D(vec3(p.x*q.distortFreq*q.distPar,p.y*q.distortFreq*q.distOrth,u_time*q.distortSpeed))*2.0-1.0)*q.distortAmp;
    if(q.waveAmp>0.0) { float w=p.y*q.waveFreq; p.x+=mix(sin(w),asin(sin(w))*.636619,q.waveShape)*q.waveAmp; }
    float fade=1.0; if(q.extent>0.0) { float d=abs(p.x*q.period)/TAU; fade=1.0-smoothstep(q.extent*.5,q.extent,d); }
    signal+=shapedSine(p.x*q.period-u_time*q.speed)*fade*q.strength;
  }
  for(int i=0;i<8;i++) {
    Pattern q=u_ripples[i]; if(q.enabled<.5) continue;
    vec2 pivot=q.pivot+movingOffset(float(i)*10.0,q.noiseMove);
    vec2 p=rotatePoint(uv,-u_time*q.rotationSpeed,q.rotationPivot), d=p-pivot;
    float radius=length(d), angle=atan(d.y,d.x);
    if(q.distortAmp>0.0) radius+=(noise3D(vec3(radius*q.distortFreq*q.distPar,angle*q.distortFreq*q.distOrth,u_time*q.distortSpeed))*2.0-1.0)*q.distortAmp;
    if(q.waveAmp>0.0) { float w=angle*q.waveFreq; radius+=mix(sin(w),asin(sin(w))*.636619,q.waveShape)*q.waveAmp; }
    signal+=shapedSine(radius*q.period-u_time*q.speed)*q.strength;
  }
  for(int i=0;i<8;i++) {
    Pattern q=u_rays[i]; if(q.enabled<.5) continue;
    vec2 pivot=q.pivot+movingOffset(float(i)*20.0,q.noiseMove);
    vec2 p=rotatePoint(uv,-u_time*q.rotationSpeed,q.rotationPivot), d=p-pivot;
    float radius=length(d), angle=atan(d.y,d.x);
    if(q.distortAmp>0.0) angle+=(noise3D(vec3(angle*q.distortFreq*q.distPar,radius*q.distortFreq*q.distOrth,u_time*q.distortSpeed))*2.0-1.0)*q.distortAmp;
    if(q.waveAmp>0.0) { float w=radius*q.waveFreq; angle+=mix(sin(w),asin(sin(w))*.636619,q.waveShape)*q.waveAmp; }
    signal+=shapedSine(angle*floor(q.period)-u_time*q.speed)*q.strength;
  }
  for(int i=0;i<8;i++) {
    Pattern q=u_perlins[i]; if(q.enabled<.5) continue;
    float value=noise3D(vec3((uv-q.pivot)*q.perlinScale,q.perlinZOffset+u_time*q.perlinZSpeed));
    signal+=(value*2.0-1.0)*q.strength;
  }
  if(u_oscActive>.5) { float o=sin(u_time*u_oscFreq); signal=sin(signal)*sign(o)*pow(abs(o),u_oscShape); }
  else signal=sin(signal);
  signal=signal*.5+.5;
  float width=u_vigEdge-u_vigCenter;
  if(u_vigEdge>0.0&&width>.0001) signal=mix(signal,u_vigBias,smoothstep(u_vigCenter,u_vigEdge,length(rawUv)));
  return signal;
}
vec3 palette(float signal) {
  return u_colorCount<3 ? mix(u_color1,u_color2,signal) :
    (signal<.5 ? mix(u_color1,u_color2,signal*2.0) : mix(u_color2,u_color3,(signal-.5)*2.0));
}
vec3 interference(vec2 uv) {
  float signal=signalAt(uv); vec3 color=palette(signal);
  float softness=clamp(u_trail*.14+clamp(u_blur/15.0,0.0,1.0)*.22,0.0,.30);
  color=mix(color,palette(mix(signal,smoothstep(.08,.92,signal),.35)),softness);
  color+=max(color-.5,0.0)*u_glow;
  color=(color-.5)*u_contrast+.5+u_brightness;
  if(u_noiseStrength>0.0) {
    float n=hash31(vec3(floor(gl_FragCoord.xy),floor(u_time*max(u_noiseFreq,.1))));
    color=mix(color,mix(color,vec3(u_noiseBias),n),u_noiseStrength);
  }
  return clamp(color,0.0,1.0);
}
vec3 temporal(vec2 uv) {
  bool first=fract(u_time*u_frequency)<u_duty;
  vec3 color=first?u_color1:u_color2;
  float enabled=first?u_noisePhase1:u_noisePhase2;
  float amp=first?u_noiseAmp1:u_noiseAmp2;
  if(enabled>.5&&amp>0.0) {
    vec2 cell=floor((uv+1.0)*256.0/max(u_noiseResolution,1.0));
    float n=u_noiseType>.5?noise3D(vec3(cell*.05,0)):hash31(vec3(cell,19.7));
    color+=vec3(n*2.0-1.0)*amp;
  }
  if(u_fixationEnabled>.5) {
    float halfSize=max(.004,u_fixationSize/700.0), thick=max(.0015,halfSize*.16);
    float cross=max(step(abs(uv.x),thick)*step(abs(uv.y),halfSize),step(abs(uv.y),thick)*step(abs(uv.x),halfSize));
    color=mix(color,u_fixationColor,cross);
  }
  return clamp(color,0.0,1.0);
}
void main() {
  vec2 uv=v_uv; uv.x*=u_resolution.x/u_resolution.y;
  fragColor=vec4(u_kind==0?interference(uv):temporal(uv),1.0);
}`;

function compile(gl, type, source) {
  const shader = gl.createShader(type); gl.shaderSource(shader, source); gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
  return shader;
}

function rgb(hex) {
  return [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255);
}

export class FlatProfileRenderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.profile = null;
    this.gl = canvas.getContext("webgl2", { alpha: false, antialias: false });
    if (!this.gl) throw new Error("WebGL2 is required for the live preview");
    const gl = this.gl;
    this.program = gl.createProgram();
    gl.attachShader(this.program, compile(gl, gl.VERTEX_SHADER, vertexSource));
    gl.attachShader(this.program, compile(gl, gl.FRAGMENT_SHADER, fragmentSource));
    gl.linkProgram(this.program);
    if (!gl.getProgramParameter(this.program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(this.program));
    const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,-1,1,1,-1,1,1]), gl.STATIC_DRAW);
    gl.useProgram(this.program);
    const position = gl.getAttribLocation(this.program, "a_position");
    gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    this.start = performance.now();
    requestAnimationFrame(time => this.render(time));
  }

  setProfile(profile) { this.profile = profile; }
  loc(name) { return this.gl.getUniformLocation(this.program, name); }
  f(name, value) { this.gl.uniform1f(this.loc(name), Number(value ?? 0)); }
  i(name, value) { this.gl.uniform1i(this.loc(name), Number(value ?? 0)); }
  v2(name, a, b) { this.gl.uniform2f(this.loc(name), Number(a ?? 0), Number(b ?? 0)); }
  v3(name, value) { this.gl.uniform3fv(this.loc(name), rgb(value)); }

  pattern(prefix, index, q) {
    const base = `${prefix}[${index}]`;
    this.f(`${base}.enabled`, q?.active ? 1 : 0);
    if (!q) return;
    for (const [uniform, key] of [
      ["strength","strength"],["period","period"],["speed","speed"],
      ["distortFreq","distort_freq"],["distortAmp","distort_amp"],["distortSpeed","distort_speed"],
      ["distPar","dist_mult_parallel"],["distOrth","dist_mult_orthogonal"],
      ["waveFreq","wave_freq"],["waveAmp","wave_amp"],["waveShape","wave_shape"],
      ["angle","angle"],["rotationSpeed","rotation_speed"],["extent","extent"],
      ["noiseMove","noise_move"],["perlinScale","perlin_scale"],
      ["perlinZSpeed","perlin_z_speed"],["perlinZOffset","perlin_z_offset"],
    ]) this.f(`${base}.${uniform}`, q[key]);
    this.v2(`${base}.pivot`, q.pivot_x, q.pivot_y);
    this.v2(`${base}.rotationPivot`, q.rotation_pivot_x, q.rotation_pivot_y);
  }

  apply(stored) {
    const p = stored.profile;
    this.i("u_kind", stored.kind === "interference" ? 0 : 1);
    this.v3("u_color1", p.color_1); this.v3("u_color2", p.color_2);
    this.v3("u_color3", p.color_3 ?? "#000000"); this.v3("u_fixationColor", p.fixation_color ?? "#ff0000");
    this.i("u_colorCount", p.color_count ?? 2);
    const values = {
      u_oscActive:p.oscillator_active?1:0,u_oscFreq:p.oscillator_frequency_hz,u_oscShape:p.oscillator_shape,
      u_scale:p.scale,u_shearX:p.shear_x,u_shearY:p.shear_y,u_offsetX:p.offset_x,u_offsetY:p.offset_y,
      u_shakeAmp:p.shake_amplitude,u_shakeFreq:p.shake_frequency_hz,u_rotSpeed:p.rotation_speed,u_stepFactor:p.step_factor,
      u_trail:p.trail_amount,u_blur:p.blur_radius,u_glow:p.glow_strength,u_brightness:p.brightness,u_contrast:p.contrast,
      u_noiseFreq:p.noise_frequency,u_noiseStrength:p.noise_strength,u_noiseBias:p.noise_bias,
      u_vigCenter:p.vignette_center,u_vigEdge:p.vignette_edge,u_vigBias:p.vignette_bias,
      u_frequency:p.frequency_hz,u_duty:(p.duty_percent??50)/100,u_noiseType:p.noise_type==="perlin"?1:0,
      u_noiseResolution:p.noise_resolution,u_noisePhase1:p.noise_phase_1?1:0,u_noiseAmp1:p.noise_amplitude_1,
      u_noisePhase2:p.noise_phase_2?1:0,u_noiseAmp2:p.noise_amplitude_2,
      u_fixationEnabled:p.fixation_enabled?1:0,u_fixationSize:p.fixation_size,
    };
    for (const [name,value] of Object.entries(values)) this.f(name,value);
    for (const [kind,prefix] of [["stripe","u_stripes"],["ripple","u_ripples"],["ray","u_rays"],["perlin","u_perlins"]]) {
      const patterns=(p.patterns??[]).filter(q=>q.kind===kind);
      for(let i=0;i<8;i++) this.pattern(prefix,i,patterns[i]);
    }
  }

  render(time) {
    const gl=this.gl, dpr=Math.min(devicePixelRatio||1,2), rect=this.canvas.getBoundingClientRect();
    const width=Math.max(1,Math.floor(rect.width*dpr)), height=Math.max(1,Math.floor(rect.height*dpr));
    if(this.canvas.width!==width||this.canvas.height!==height){this.canvas.width=width;this.canvas.height=height;}
    gl.viewport(0,0,width,height); gl.useProgram(this.program);
    gl.uniform2f(this.loc("u_resolution"),width,height); this.f("u_time",(time-this.start)/1000);
    if(this.profile) this.apply(this.profile); else gl.clearColor(0,0,0,1),gl.clear(gl.COLOR_BUFFER_BIT);
    if(this.profile) gl.drawArrays(gl.TRIANGLES,0,6);
    requestAnimationFrame(next=>this.render(next));
  }
}
