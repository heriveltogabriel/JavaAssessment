'use strict';
// Small CSV parser for downloaded test artifacts, including quoted multiline fields.
function parseCsv(input){
 const text=String(input).replace(/^\uFEFF/,''),rows=[];let row=[],cell='',quoted=false;
 for(let i=0;i<text.length;i++){const c=text[i];if(c==='"'){if(quoted&&text[i+1]==='"'){cell+='"';i++;}else quoted=!quoted;}else if(!quoted&&c===','){row.push(cell);cell='';}else if(!quoted&&(c==='\r'||c==='\n')){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cell);rows.push(row);row=[];cell='';}else cell+=c;}
 if(quoted)throw Error('Unclosed CSV quote');if(row.length||cell){row.push(cell);rows.push(row);}const headers=rows.shift()||[];
 return rows.map(values=>{if(values.length!==headers.length)throw Error('CSV column count');return Object.fromEntries(headers.map((h,i)=>[h,values[i]]));});
}
module.exports={parseCsv};
