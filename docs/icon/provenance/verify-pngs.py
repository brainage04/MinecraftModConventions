"""Decode final images, verify projected geometry coverage, and assemble review evidence."""
import hashlib
import json
import math
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw
ROOT = Path(__file__).resolve().parent


def dump(name,value):
    (ROOT/name).write_text(json.dumps(value,indent=2)+'\n')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def foreground(image,background):
    difference=ImageChops.difference(image,Image.new('RGB',image.size,background))
    channels=difference.split()
    return ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2]).point(lambda p:255 if p else 0)


def main():
    candidates=json.loads((ROOT/'candidates.json').read_text())
    assert len(candidates) == 1
    assert sorted(p.name for p in ROOT.glob('conventions2-*.png')) == [candidates[0]['name']+'.png']
    refs=json.loads((ROOT/'evidence/reference-measurements.json').read_text())['references']
    reference=Image.open(ROOT.parent/'blender-q/modern-hub-down-left-1.png').convert('RGB')
    background=reference.getpixel((0,0))
    reference_bbox=foreground(reference,background).getbbox()
    reference_margins=[reference_bbox[0],1024-reference_bbox[2],reference_bbox[1],1024-reference_bbox[3]]
    checks=[]
    entries=[]
    reports=[]
    family=Image.new('RGB',(768,256),background)
    for i,era in enumerate(['legacy','modern']):
        family.paste(Image.open(ROOT.parent/'blender-q'/(era+'-hub-down-left-1.png')).convert('RGB').resize((256,256),Image.Resampling.LANCZOS),(i*256,0))
    for index,candidate in enumerate(candidates):
        name=candidate['name']
        meta=json.loads((ROOT/(name+'-metadata.json')).read_text())
        proof=json.loads((ROOT/(name+'-verification.json')).read_text())
        image=Image.open(ROOT/(name+'.png'))
        assert image.format=='PNG' and image.size==(1024,1024)
        image.load()
        assert image.convert('RGBA').getchannel('A').getextrema()==(255,255)
        assert sha(ROOT/(name+'.png'))==meta['png_sha256']
        assert sha(ROOT/(name+'.blend'))==meta['blend_sha256']
        for filename,digest in meta['author_scripts_sha256'].items():
            assert sha(ROOT/filename)==digest
        rgb=image.convert('RGB')
        assert all(rgb.getpixel(p)==background for p in [(0,0),(1023,0),(0,1023),(1023,1023)])
        mask=foreground(rgb,background)
        bb=mask.getbbox()
        margins=[bb[0],1024-bb[2],bb[1],1024-bb[3]]
        assert min(margins)>=35 and max(abs(a-b) for a,b in zip(margins,reference_margins))<70
        expected=Image.new('L',(1024,1024),0)
        draw=ImageDraw.Draw(expected)
        regions=[]
        for block,bound in meta['geometry']['station_bounds_normalized_xy_y_up'].items():
            box=[max(0,math.floor(bound[0]*1024)-2),max(0,math.floor((1-bound[3])*1024)-2),min(1024,math.ceil(bound[2]*1024)+2),min(1024,math.ceil((1-bound[1])*1024)+2)]
            draw.rectangle(box,fill=255)
            region=mask.crop(box)
            count=region.histogram()[255]
            area=region.width*region.height
            assert count>500 and count/area>.1, (name,block,count,area)
            colors=rgb.crop(box).getcolors(region.width*region.height)
            assert len(colors)>20,(name,block,'missing real texture variation')
            regions.append({'block':block,'projected_region_pixels':box,'foreground_pixels':count,'foreground_fraction':count/area,'visible_texture_color_count':len(colors)})
        outside=ImageChops.subtract(mask,expected)
        assert outside.getbbox() is None, (name,'Pixels outside seven official block bounds')
        camera=meta['geometry']['camera']
        assert camera==refs['modern']['measured']
        assert proof['reopened_saved_blend'] and proof['embedded_author_scripts_match_disk']
        png_check={'decoded_png':True,'resolution':[1024,1024],'opaque':True,'background_rgb':list(background),'background_matches_modern_reference':True,'foreground_bbox_pixels':list(bb),'margins_pixels_left_right_top_bottom':margins,'reference_margins_pixels_left_right_top_bottom':reference_margins,'seven_visible_block_regions':regions,'no_pixels_outside_projected_blocks':True,'no_floor_shadow_glow_or_overlay':True,'png_sha256':meta['png_sha256']}
        proof['png']=png_check
        dump(name+'-verification.json',proof)
        checks.append({'name':name,**png_check})
        thumb=rgb.resize((128,128),Image.Resampling.LANCZOS)
        thumb.save(ROOT/'evidence'/(name+'-128.png'))
        family.paste(rgb.resize((256,256),Image.Resampling.LANCZOS),((index+2)*256,0))
        notes=f"Owner-selected exact block layout, replacing and retiring all five earlier conventions2 candidates. {candidate['idea']} Exact modern camera, seven slot positions, unit block scale, vanilla daylight shader and near-black reference background retained; no floor, architecture, connectors, effects or text. Measured yaw {camera['measured_yaw_degrees']:.9f} degrees, pitch {camera['measured_pitch_degrees']:.9f} degrees; zero camera delta from modern reference. Blender {meta['blender_version']}; Cycles CPU; {meta['samples']} samples; {meta['render_duration_seconds']:.6f}s. Saved .py, packed .blend, metadata and verification accompany this 1024x1024 PNG. Scripts are saved untracked and hash-recorded, not committed, per local-only policy."
        entries.append({'project':'MinecraftModConventions','label':candidate['label'],'path':'blender-ab/'+name+'.png','method':'Headless Blender CLI, Cycles CPU, 2 threads, CPUWeight=20, shared quota<=3 cores; exact reference scene camera and shader with canonical client-jar block models','source':'Minecraft 26.2 client assets from /home/thomas/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar; blender-q/modern-hub-down-left-1.blend and facing-table.json; see asset-provenance.json and source-snapshot.json','notes':notes})
        reports.append({key:meta[key] for key in ['name','blender_version','engine','device','samples','render_duration_seconds','resolution','resources']})
    family.save(ROOT/'evidence/template-family-comparison.png')
    dump('png-verification.json',{'images':checks,'single_owner_selected_candidate':True})
    dump('manifest.json',{'entries':entries})
    dump('render-report.json',reports)
    dump('camera-comparison.json',{'references':{era:refs[era]['measured'] for era in refs},'candidates':{c['name']:json.loads((ROOT/(c['name']+'-metadata.json')).read_text())['geometry']['camera'] for c in candidates},'measured_camera_delta_from_modern_degrees':[0,0],'nominal_yaw_degrees':45,'nominal_pitch_degrees':math.degrees(math.atan(1/math.sqrt(2))),'modern_orthographic_scale_preserved':True,'legacy_orthographic_scale_note':'Legacy uses 7.15; candidates use the modern reference 6.90, not a rescaled compromise.','background_note':'Exact modern near-black linear RGB (0.0025, 0.0035, 0.006), strength 1, preserved per parent clarification; decoded RGB '+str(background)})
    dump('evidence/contact-index.json',{'template-family-comparison.png':'Legacy reference, Modern reference, then the single owner-selected layout at 256x256. No annotations are painted into images.','conventions2-selected-owner-layout-128.png':'The selected layout at 128x128. Previous five-candidate evidence is archived under removed/blender-ab/evidence.'})
    dump('blockers.json',[])
    print(json.dumps({'verified_images':len(checks),'background_rgb':background,'margins_pixels':checks[0]['margins_pixels_left_right_top_bottom'],'render_report':reports},indent=2))


if __name__=='__main__':
    main()
