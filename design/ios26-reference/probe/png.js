const fs=require('fs'), zlib=require('zlib');
function decode(path){
  const b=fs.readFileSync(path); let off=8; let ihdr=null, idat=[], plte=null;
  while(off<b.length){ const len=b.readUInt32BE(off); const type=b.toString('ascii',off+4,off+8);
    const data=b.slice(off+8,off+8+len);
    if(type==='IHDR') ihdr={w:data.readUInt32BE(0),h:data.readUInt32BE(4),depth:data[8],color:data[9],interlace:data[12]};
    else if(type==='IDAT') idat.push(data);
    else if(type==='IEND') break;
    off+=12+len; }
  const raw=zlib.inflateSync(Buffer.concat(idat));
  const ch={0:1,2:3,3:1,4:2,6:4}[ihdr.color]; const bpp=ch*(ihdr.depth/8);
  const stride=ihdr.w*bpp; const out=Buffer.alloc(ihdr.h*stride);
  let p=0;
  for(let y=0;y<ihdr.h;y++){ const f=raw[p++]; const line=raw.slice(p,p+stride); p+=stride;
    const cur=out.slice(y*stride,(y+1)*stride); const prev=y>0?out.slice((y-1)*stride,y*stride):null;
    for(let x=0;x<stride;x++){ const a=x>=bpp?cur[x-bpp]:0; const bb=prev?prev[x]:0; const c=(x>=bpp&&prev)?prev[x-bpp]:0; let v=line[x];
      switch(f){case 0:break;case 1:v+=a;break;case 2:v+=bb;break;case 3:v+=(a+bb)>>1;break;
        case 4:{const pa=Math.abs(bb-c),pb=Math.abs(a-c),pc=Math.abs(a+bb-2*c); v+= (pa<=pb&&pa<=pc)?a:(pb<=pc?bb:c);}break;}
      cur[x]=v&255; } }
  return {w:ihdr.w,h:ihdr.h,ch,data:out,stride};
}
function px(img,x,y){const i=y*img.stride+x*img.ch; return [img.data[i],img.data[i+1],img.data[i+2]];}
module.exports={decode,px};
