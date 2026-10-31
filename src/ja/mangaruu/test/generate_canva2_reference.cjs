const fs = require('fs');
const crypto = require('crypto');
const [wasmPath, outputPath] = process.argv.slice(2);
if (!wasmPath || !outputPath) throw Error('Usage: node generate_canva2_reference.cjs <soraraw.wasm> <output.json>');
const instance = new WebAssembly.Instance(new WebAssembly.Module(fs.readFileSync(wasmPath)), {});
function reference(width, height, chapterId) {
  const {memory,malloc,free,unscramble} = instance.exports;
  const size=width*height*4, data=malloc(size), key=Buffer.from(chapterId+'\0'), id=malloc(key.length);
  let bytes=new Uint8Array(memory.buffer);
  for(let p=0;p<width*height;p++) {bytes[data+p*4]=p&255;bytes[data+p*4+1]=(p>>>8)&255;bytes[data+p*4+2]=(p>>>16)&255;bytes[data+p*4+3]=255;}
  bytes.set(key,id);
  const code=unscramble(data,width,height,4,8,id); if(code!==0)throw Error('unscramble returned '+code);
  bytes=new Uint8Array(memory.buffer);
  const indices=new Int32Array(width*height), argb=Buffer.alloc(size);
  for(let p=0;p<indices.length;p++){const q=data+p*4; indices[p]=bytes[q]|(bytes[q+1]<<8)|(bytes[q+2]<<16);argb[p*4]=255;argb[p*4+1]=bytes[q];argb[p*4+2]=bytes[q+1];argb[p*4+3]=bytes[q+2];}
  const tiles=[];let y=0;
  for(let row=0;row<8;row++){const h=Math.floor(height/8)+(row<height%8?1:0);let x=0;
    for(let col=0;col<8;col++){const w=Math.floor(width/8)+(col<width%8?1:0), start=indices[y*width+x], dx=indices[y*width+x+1]-start, dy=indices[(y+1)*width+x]-start;
      for(let r=0;r<h;r++)for(let c=0;c<w;c++)if(indices[(y+r)*width+x+c]!==start+c*dx+r*dy)throw Error('Non-affine tile');
      tiles.push({x,y,width:w,height:h,source:start,dx,dy});x+=w;
    }y+=h;
  }
  free(id);free(data);
  return {width,height,chapterId,sha256:crypto.createHash('sha256').update(argb).digest('hex'),tiles};
}
const cases=[[64,64,'895177'],[65,81,'895177'],[720,1024,'895177'],[1337,1920,'895177'],[127,127,'178151'],[65,65,'141124']].map(a=>reference(...a));
fs.writeFileSync(outputPath,JSON.stringify({wasmSha256:crypto.createHash('sha256').update(fs.readFileSync(wasmPath)).digest('hex'),source:'https://mangaruu.com/soraraw.wasm',cases}));
console.log(cases.map(({width,height,chapterId,sha256})=>({width,height,chapterId,sha256})));
