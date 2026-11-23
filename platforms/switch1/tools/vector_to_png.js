'use strict';
const fs=require('fs');
const {Resvg}=require('@resvg/resvg-js');
if(process.argv.length!==4) throw Error('usage: node vector_to_png.js input.svg output.png');
const svg=fs.readFileSync(process.argv[2], 'utf8');
const v=new Resvg(svg, {fitTo:{mode:'zoom',value:2}});
fs.writeFileSync(process.argv[3],v.render().asPng());
