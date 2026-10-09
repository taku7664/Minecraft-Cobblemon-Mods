param([string]$SourceRoot=(Join-Path $env:USERPROFILE 'Documents/GitHub/Cobblemon-Mods/develop-product/client'),[string]$TargetRoot=(Join-Path $env:APPDATA 'ModrinthApp/profiles/PPakemon'),[string]$WikiRoot=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../server-wiki'))),[string]$BackupRoot,[ValidateSet('Window','Preview','Apply','Restore')][string]$Mode='Window',[switch]$LibraryOnly)
$ErrorActionPreference='Stop'
$modulePath=Join-Path $PSScriptRoot 'client-patch.psm1'
function New-ClientPatchWindow([string]$Source,[string]$Target,[string]$State,[switch]$QuietErrors) {
    $runtimeModule=$modulePath; $wikiRuntime=$WikiRoot
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $form=[Windows.Forms.Form]::new()
    $form.Text='빡케몬 클라이언트 패치';$form.ClientSize=[Drawing.Size]::new(1020,740)
    $form.MinimumSize=[Drawing.Size]::new(900,700);$form.StartPosition='CenterScreen'
    $form.Font=[Drawing.Font]::new('맑은 고딕',10);$form.BackColor=[Drawing.Color]::FromArgb(246,248,251)
    function Label($text,$x,$y,$width,$height) {
        $control=[Windows.Forms.Label]::new();$control.Text=$text;$control.Location=[Drawing.Point]::new($x,$y);$control.Size=[Drawing.Size]::new($width,$height);$form.Controls.Add($control);return $control
    }
    function Button($text,$x,$y,$width) {
        $control=[Windows.Forms.Button]::new();$control.Text=$text;$control.Location=[Drawing.Point]::new($x,$y);$control.Size=[Drawing.Size]::new($width,38);$control.FlatStyle='Flat';$control.BackColor=[Drawing.Color]::White;$form.Controls.Add($control);return $control
    }
    $title=Label '빡케몬 클라이언트 패치' 24 20 700 34;$title.Font=[Drawing.Font]::new('맑은 고딕',18,[Drawing.FontStyle]::Bold)
    $null=Label 'Minecraft를 종료하고 게임 폴더를 선택한 뒤 변경 목록을 검사하세요.' 26 63 940 27
    $null=Label '개발 클라' 24 108 100 28
    $null=Label '클라이언트' 24 155 100 28
    $sourceBox=[Windows.Forms.TextBox]::new();$sourceBox.Text=$Source;$sourceBox.SetBounds(126,104,758,30);$sourceBox.Anchor='Top,Left,Right';$form.Controls.Add($sourceBox)
    $targetBox=[Windows.Forms.TextBox]::new();$targetBox.Text=$Target;$targetBox.SetBounds(126,151,758,30);$targetBox.Anchor='Top,Left,Right';$form.Controls.Add($targetBox)
    $chooseSource=Button '찾기' 900 100 94;$chooseSource.Anchor='Top,Right'
    $chooseTarget=Button '찾기' 900 147 94;$chooseTarget.Anchor='Top,Right'
    $scope=Label "업데이트: 모드 · 시작 훅 · 위키 · 지정 기능 리소스팩`r`n보존: 개인 설정 · 단축키 · 적용 기록 · 서버 목록 · 월드 · 지도 · 개인 추가 모드" 26 202 965 56;$scope.Anchor='Top,Left,Right'
    $inspect=Button '1. 변경 목록 검사' 24 274 182
    $deploy=Button '2. 변경사항 패치' 220 274 182;$deploy.Enabled=$false;$deploy.BackColor=[Drawing.Color]::FromArgb(224,237,255)
    $restore=Button '최근 패치 복구' 814 274 180;$restore.Anchor='Top,Right'
    $summary=Label '검사하면 추가·교체·삭제할 파일을 표시합니다.' 26 325 965 30;$summary.Anchor='Top,Left,Right'
    $grid=[Windows.Forms.DataGridView]::new();$grid.SetBounds(24,365,970,240);$grid.Anchor='Top,Bottom,Left,Right'
    $grid.ReadOnly=$true;$grid.AllowUserToAddRows=$false;$grid.AllowUserToDeleteRows=$false;$grid.RowHeadersVisible=$false
    $grid.BackgroundColor=[Drawing.Color]::White;$grid.BorderStyle='None';$grid.AutoSizeColumnsMode='Fill';$grid.SelectionMode='FullRowSelect';$grid.MultiSelect=$false
    $grid.EnableHeadersVisualStyles=$false;$grid.ColumnHeadersDefaultCellStyle.BackColor=[Drawing.Color]::FromArgb(231,236,243)
    foreach($column in @(@('Action','변경',12),@('Path','파일',73),@('Size','용량',15))){$index=$grid.Columns.Add($column[0],$column[1]);$grid.Columns[$index].FillWeight=$column[2]}
    $form.Controls.Add($grid)
    $progress=[Windows.Forms.ProgressBar]::new();$progress.SetBounds(24,620,970,14);$progress.Anchor='Bottom,Left,Right';$form.Controls.Add($progress)
    $status=Label '대기 중' 26 646 965 47;$status.Anchor='Bottom,Left,Right'
    $note=Label '교체 파일을 게임 폴더 밖에 백업합니다. 개인 설정과 월드는 변경하지 않습니다.' 26 700 970 25;$note.Font=[Drawing.Font]::new('맑은 고딕',9);$note.Anchor='Bottom,Left,Right'
    $context=@{Plan=$null;Busy=$false;Worker=$null;Handle=$null;Queue=$null;Action=$null;State=$State;Error=$null}
    $inputs=@($sourceBox,$targetBox,$chooseSource,$chooseTarget,$inspect,$restore)
    $invalidate={if(-not $context.Busy){$context.Plan=$null;$deploy.Enabled=$false;$summary.Text='폴더가 바뀌었습니다. 변경 목록을 다시 검사하세요.'}}.GetNewClosure()
    $sourceBox.Add_TextChanged($invalidate);$targetBox.Add_TextChanged($invalidate)
    $selectFolder={param($box) $dialog=[Windows.Forms.FolderBrowserDialog]::new();$dialog.Description='게임 폴더를 선택하세요';$dialog.ShowNewFolderButton=$false;$dialog.SelectedPath=$box.Text;try{if($dialog.ShowDialog($form) -eq 'OK'){$box.Text=$dialog.SelectedPath}}finally{$dialog.Dispose()}}.GetNewClosure()
    $chooseSource.Add_Click({& $selectFolder $sourceBox}.GetNewClosure());$chooseTarget.Add_Click({& $selectFolder $targetBox}.GetNewClosure())
    $timer=[Windows.Forms.Timer]::new();$timer.Interval=150
    $start={param($action)
        if($context.Busy){return}
        $context.Busy=$true;$context.Action=$action;$context.Error=$null;$deploy.Enabled=$false;foreach($control in $inputs){$control.Enabled=$false}
        $status.Text=$(switch($action){'inspect'{'파일과 클라이언트 종료 상태를 검사하고 있습니다…'}'deploy'{'패치 파일과 원본 백업을 준비하고 있습니다…'}'restore'{'최근 패치의 원본을 확인하고 복구하고 있습니다…'}})
        $progress.Style='Marquee';$context.Queue=[Collections.Concurrent.ConcurrentQueue[object]]::new()
        $worker=[Management.Automation.PowerShell]::Create()
        $null=$worker.AddScript({param($module,$action,$source,$target,$state,$plan,$queue,$wiki)
            $ErrorActionPreference='Stop';Import-Module $module -Force
            switch($action){
                'inspect' {Get-ClientPatchPlan -SourceRoot $source -WikiRoot $wiki -TargetRoot $target -BackupRoot $state}
                'deploy' {Invoke-ClientPatch -Plan $plan -ProgressCallback {param($stage,$current,$total,$path) $queue.Enqueue(@{Stage=$stage;Current=$current;Total=$total;Path=$path})}.GetNewClosure()}
                'restore' {Restore-ClientPatch -TargetRoot $target -BackupRoot $state}
            }
        }).AddArgument($runtimeModule).AddArgument($action).AddArgument($sourceBox.Text).AddArgument($targetBox.Text).AddArgument($context.State).AddArgument($context.Plan).AddArgument($context.Queue).AddArgument($wikiRuntime)
        $context.Worker=$worker
        try{$context.Handle=$worker.BeginInvoke();$timer.Start()}
        catch{$worker.Dispose();$context.Worker=$null;$context.Busy=$false;foreach($control in $inputs){$control.Enabled=$true};$progress.Style='Blocks';$context.Error=$_.Exception.Message;$status.Text=$context.Error}
    }.GetNewClosure()
    $inspect.Add_Click({& $start 'inspect'}.GetNewClosure());$deploy.Add_Click({& $start 'deploy'}.GetNewClosure());$restore.Add_Click({& $start 'restore'}.GetNewClosure())
    $timer.Add_Tick({
        $item=$null;$last=$null;while($context.Queue.TryDequeue([ref]$item)){$last=$item}
        if($null -ne $last){$progress.Style='Blocks';$progress.Value=[Math]::Min(100,[int](100*$last.Current/[Math]::Max(1,$last.Total)));$status.Text=$(switch($last.Stage){'data'{'기존 월드 백업: '}'stage'{'교체 파일 준비: '}default{'패치 중: '}})+$last.Path}
        if(-not $context.Handle.IsCompleted){return}
        $timer.Stop()
        try{
            $results=$context.Worker.EndInvoke($context.Handle)
            if($context.Worker.HadErrors){throw $context.Worker.Streams.Error[0].Exception.Message}
            $result=$results[0]
            if($context.Action -eq 'inspect'){
                $context.Plan=$result;$grid.Rows.Clear()
                foreach($op in $result.Operations){$name=switch($op.Action){'add'{'추가'}'update'{'교체'}'delete'{'삭제'}};$size=if($op.Action -eq 'delete'){'—'}else{('{0:N1} MB' -f ($op.Size/1MB))};$null=$grid.Rows.Add($name,$op.Path,$size)}
                $counts=@{};foreach($name in @('add','update','delete')){$counts[$name]=@($result.Operations|Where-Object {$_.Action -eq $name}).Count}
                $summary.Text=('추가 {0} · 교체 {1} · 삭제 {2}   |   복사 {3:N1} MB' -f $counts.add,$counts.update,$counts.delete,($result.TotalBytes/1MB))
                $status.Text=if($result.Operations.Count -eq 0){'이미 동일합니다. 패치할 변경사항이 없습니다.'}elseif($result.Warnings.Count){'검사 완료. 보존·제외 항목 '+$result.Warnings.Count+'개: '+($result.Warnings -join ' / ')}else{'검사 완료. 위 변경 목록을 확인한 뒤 패치 버튼을 누르세요.'}
                $deploy.Enabled=$result.Operations.Count -gt 0
            }else{
                $context.Plan=$null;$grid.Rows.Clear();$summary.Text='작업이 완료됐습니다. 다음 패치 전 변경 목록을 다시 검사하세요.'
                $status.Text=if($context.Action -eq 'restore'){'복구 완료. 이전 패치 전 상태로 돌아갔습니다.'}elseif($result.Status -eq 'unchanged'){'이미 동일합니다.'}else{'패치 완료: '+$result.Changed+'개 파일. 백업: '+$result.BackupRoot}
            }
        }catch{
            $context.Plan=$null;$deploy.Enabled=$false;$context.Error=$_.Exception.Message;$status.Text='작업 중단: '+$context.Error
            if(-not $QuietErrors){[Windows.Forms.MessageBox]::Show($form,$context.Error,'클라이언트 패치 중단','OK','Warning')|Out-Null}
        }finally{
            $context.Worker.Dispose();$context.Worker=$null;$context.Handle=$null;$context.Busy=$false
            foreach($control in $inputs){$control.Enabled=$true};$progress.Style='Blocks';$progress.Value=0
        }
    }.GetNewClosure())
    $form.Add_FormClosing({param($sender,$event) if($context.Busy){$event.Cancel=$true;$status.Text='작업 완료 또는 복구까지 창을 열어 두세요.'}}.GetNewClosure())
    $form.Add_FormClosed({$timer.Dispose()}.GetNewClosure())
    return [pscustomobject]@{Form=$form;Inspect=$inspect;Deploy=$deploy;Restore=$restore;Grid=$grid;Status=$status;Context=$context;Start=$start;Timer=$timer}
}
if($LibraryOnly){Import-Module $modulePath -Force;return}
try {
    Import-Module $modulePath -Force
    switch($Mode) {
        'Preview' {Get-ClientPatchPlan -SourceRoot $SourceRoot -WikiRoot $WikiRoot -TargetRoot $TargetRoot -BackupRoot $BackupRoot}
        'Apply' {Invoke-ClientPatch (Get-ClientPatchPlan -SourceRoot $SourceRoot -WikiRoot $WikiRoot -TargetRoot $TargetRoot -BackupRoot $BackupRoot)}
        'Restore' {Restore-ClientPatch $TargetRoot $BackupRoot}
        'Window' {$window=New-ClientPatchWindow $SourceRoot $TargetRoot $BackupRoot;try{$window.Form.ShowDialog()|Out-Null}finally{$window.Form.Dispose()}}
    }
} catch {
    if($Mode -eq 'Window'){Add-Type -AssemblyName System.Windows.Forms;[Windows.Forms.MessageBox]::Show($_.Exception.Message,'클라이언트 패치 실행 실패','OK','Error')|Out-Null}
    else{Write-Error $_;exit 1}
}