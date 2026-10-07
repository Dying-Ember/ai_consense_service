"""Bound, CPU-only complete chat token observations for the vetting CLI adapter.

The sealed recipe decides whether its context is an offline plan or a separately
verified deployment configuration. This tool never starts a model or server and
does not promote an offline context plan to live deployment evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

PROTOCOL = 'vetting-bound-token-budget-cli-recipe-v1'
SCOPE = 'complete_serialized_messages_with_gateway_schema_envelope'

def compact(value):
    return json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False)

def sha(value):
    return hashlib.sha256(value).hexdigest()

def require(value, message):
    if not value: raise ValueError(message)

def strict_json(raw):
    def object_pairs(pairs):
        result={}
        for key,value in pairs:
            require(key not in result,'Duplicate JSON field')
            result[key]=value
        return result
    def invalid_constant(value):
        raise ValueError('Non-finite JSON number')
    return json.loads(raw,object_pairs_hook=object_pairs,parse_constant=invalid_constant)

def verify_artifact(d):
    require(set(d)=={'path','bytes','sha256'},'Software descriptor fields invalid')
    p=Path(d['path']);require(p.is_absolute() and p.is_file(),'Software artifact missing')
    b=p.read_bytes()
    require(len(b)==d['bytes'] and sha(b)==d['sha256'],'Software artifact identity changed')
    return p.resolve()

def observe(recipe, data):
    require(recipe.get('protocol')==PROTOCOL,'Recipe protocol mismatch')
    require(recipe.get('contextEvidenceScope') in ('offline_plan','current_deployment_configuration_bound'),'Context evidence scope missing')
    require(recipe.get('enableThinking') is False,'Unsupported chat-template thinking configuration')
    artifacts=recipe.get('softwareArtifacts');require(isinstance(artifacts,list) and artifacts,'Software identity missing')
    verified={str(verify_artifact(d)):d for d in artifacts}
    require(str(Path(__file__).resolve()) in verified,'CLI source not sealed in recipe')
    require(str(Path(sys.executable).resolve()) in verified,'Interpreter not sealed in recipe')
    keys={'provider','model','providerConfigurationSha256','sourceSnapshotSha256','serializedInputSha256','messages','schema','outputReserveTokens'}
    require(isinstance(data,dict) and set(data)==keys,'Complete input fields differ')
    for name in ('provider','model','providerConfigurationSha256','outputReserveTokens'):
        require(data[name]==recipe.get(name),'Input does not match bound recipe: '+name)
    for name in ('providerConfigurationSha256','sourceSnapshotSha256','serializedInputSha256'):
        require(isinstance(data[name],str) and len(data[name])==64 and all(c in '0123456789abcdef'for c in data[name]),'Input SHA invalid')
    require(type(data['outputReserveTokens']) is int and data['outputReserveTokens']>0,'Output reserve invalid')
    require(type(recipe.get('effectiveContextTokens')) is int and recipe['effectiveContextTokens']>0,'Effective context missing')
    require(isinstance(data['schema'],dict),'Schema envelope missing')
    binding={k:data[k]for k in ('provider','model','providerConfigurationSha256','messages','schema','outputReserveTokens','sourceSnapshotSha256')}
    require(sha(compact(binding).encode('utf-8'))==data['serializedInputSha256'],'Serialized gateway input SHA mismatch')
    messages=data['messages']
    require(isinstance(messages,list) and len(messages)==2,'Unsupported message history')
    for m,role in zip(messages,('system','user')):
        require(isinstance(m,dict) and set(m)=={'role','content'} and m['role']==role and isinstance(m['content'],str),'Unsupported message scope')
    require(messages[0]['content'].endswith('\nReturn only a JSON array conforming to this schema:\n'+compact(data['schema'])),'Actual gateway schema envelope missing or changed')
    exe=Path(recipe['nativeTokenizerExecutable']).resolve()
    require(str(exe) in verified,'Native tokenizer executable not sealed')
    directory=Path(recipe['nativeTokenizerResourceDirectory']).resolve()
    resources={}
    for name in ('tokenizer.json','tokenizer_config.json','generation_config.json','chat_template.jinja'):
        p=(directory/name).resolve();require(str(p)in verified,'Tokenizer resource not sealed')
        resources[name]=verified[str(p)]['sha256']
    tokenizer_identity=sha(compact({'algorithm':'ninfer-native-text-untruncated-v1','tokenizer':resources['tokenizer.json'],
        'config':resources['tokenizer_config.json'],'generationConfig':resources['generation_config.json'],
        'nativeExecutable':verified[str(exe)]['sha256']}).encode())
    template_identity=sha(compact({'template':resources['chat_template.jinja'],'enableThinking':False,
        'addGenerationPrompt':True,'continueFinalMessage':False,'literalSpansPreserved':True,
        'nativeExecutable':verified[str(exe)]['sha256']}).encode())
    require(tokenizer_identity==recipe.get('tokenizerIdentitySha256'),'Tokenizer semantic identity differs')
    require(template_identity==recipe.get('chatTemplateIdentitySha256'),'Template semantic identity differs')
    context_binding=recipe.get('contextBinding')
    require(isinstance(context_binding,dict) and context_binding.get('scope')==recipe['contextEvidenceScope'],'Context binding scope differs')
    require(context_binding.get('model')==data['model'] and context_binding.get('providerConfigurationSha256')==data['providerConfigurationSha256']
        and context_binding.get('effectiveContextTokens')==recipe['effectiveContextTokens'] and context_binding.get('outputReserveTokens')==data['outputReserveTokens'],
        'Context configuration differs')
    require(sha(compact(context_binding).encode())==recipe.get('effectiveContextIdentitySha256'),'Context identity differs')
    # ProcessBuilder-equivalent argv; credentials and user environment are not inherited.
    request=compact({'messages':messages,'enableThinking':False}).encode('utf-8')
    p=subprocess.run([str(exe),str(directory)],input=request,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
        timeout=recipe.get('nativeTokenizerTimeoutSeconds',30),env={})
    require(p.returncode==0,'Native CPU tokenizer failed')
    require(0<len(p.stdout)<=recipe.get('nativeTokenizerMaxOutputBytes',16*1024*1024),'Native tokenizer output limit exceeded')
    native=strict_json(p.stdout)
    require(native.get('protocol')=='ninfer-native-text-chat-untruncated-tokenization-v1' and native.get('truncation') is False
        and native.get('weightsLoaded') is False and native.get('generationCalls')==0,'Native tokenizer scope invalid')
    count=native.get('inputTokens');require(type(count)is int and count>0 and len(native.get('inputIds',[]))==count,'Native token count invalid')
    return {'model':data['model'],'serializedInputSha256':data['serializedInputSha256'],
        'tokenizerIdentitySha256':tokenizer_identity,'chatTemplateIdentitySha256':template_identity,
        'effectiveContextIdentitySha256':recipe['effectiveContextIdentitySha256'],'inputTokens':count,
        'effectiveContextTokens':recipe['effectiveContextTokens'],'outputReserveTokens':data['outputReserveTokens'],
        'completeChatTemplateAndSchemaObserved':True,'scope':SCOPE}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recipe',required=True);args=parser.parse_args()
    try:
        path=Path(args.recipe).resolve();raw=path.read_bytes();require(len(raw)<=1024*1024,'Recipe too large')
        recipe=strict_json(raw)
        require(recipe.get('command')==[str(Path(sys.executable).resolve()),str(Path(__file__).resolve()),'--recipe',str(path)],'CLI command differs from sealed recipe')
        input_raw=sys.stdin.buffer.read(32*1024*1024+1);require(len(input_raw)<=32*1024*1024,'Input too large')
        data=strict_json(input_raw)
        result=observe(recipe,data)
        require(path.read_bytes()==raw,'Recipe changed during observation')
        sys.stdout.write(compact(result))
    except Exception as e:
        # Input content and credentials never appear in failure output.
        sys.stderr.write(type(e).__name__+': '+str(e));raise SystemExit(2)

if __name__=='__main__':main()
