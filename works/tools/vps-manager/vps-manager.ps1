param(
    [string]$SettingsPath = (Join-Path $PSScriptRoot 'settings.json'),
    [string]$RenderUiPath,
    [switch]$Probe,
    [ValidateSet('status','plan','start')][string]$ProbeAction = 'status'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
$settings = Get-Content -LiteralPath $SettingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($settings.host -notmatch '^[a-zA-Z0-9_-]+@[a-zA-Z0-9.-]+$' -or $settings.session -notmatch '^[a-zA-Z0-9_-]+$') {
    throw '연결 설정의 호스트·세션 이름이 올바르지 않습니다.'
}
if (-not (Test-Path -LiteralPath $settings.keyPath)) { throw 'SSH 키 파일을 찾을 수 없습니다. settings.json을 확인해주세요.' }
$backend = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'remote_manager.py') -Raw -Encoding UTF8
$utf8 = [System.Text.UTF8Encoding]::new($false)
$source64 = [Convert]::ToBase64String($utf8.GetBytes($backend))
$script:busy = $false
$script:process = $null
$script:job = $null
$script:head = $null
$script:plan = $null
$script:mode = ''
$script:nextPoll = [DateTime]::MinValue
$script:failures = 0
$script:lastError = $null

function New-Request([string]$Action) {
    return @{action=$Action;root=$settings.root;session=$settings.session;author_name=$settings.authorName;author_email=$settings.authorEmail}
}
function Start-Rpc($Request, [string]$Mode) {
    if ($script:process) { throw '이전 요청이 아직 진행 중입니다.' }
    $payload = [Convert]::ToBase64String($utf8.GetBytes(($Request | ConvertTo-Json -Depth 8 -Compress)))
    $workerSource = if($Request.action -in @('update','commit','push','start','stop')) { $source64 } else { '' }
    $source = $backend + "`n" + "rpc('$payload', '$workerSource')`n"
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $settings.sshPath
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardInputEncoding = $utf8
    $info.StandardOutputEncoding = $utf8
    $info.StandardErrorEncoding = $utf8
    foreach ($arg in @('-T','-i',$settings.keyPath,'-p',[string]$settings.port,'-o','BatchMode=yes',
            '-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15',
            '-o','ServerAliveCountMax=3',$settings.host,'python3 -')) {
        $info.ArgumentList.Add($arg)
    }
    $script:process = [System.Diagnostics.Process]::new()
    $script:process.StartInfo = $info
    [void]$script:process.Start()
    $script:stdoutTask = $script:process.StandardOutput.ReadToEndAsync()
    $script:stderrTask = $script:process.StandardError.ReadToEndAsync()
    $script:inputTask = $script:process.StandardInput.WriteAsync($source)
    $script:inputClosed = $false
    $script:mode = $Mode
}
function Set-Busy([bool]$Value, [string]$Message) {
    $script:busy = $Value
    foreach ($control in $actionControls) { $control.Enabled = -not $Value }
    $apply.Enabled = (-not $Value -and $null -ne $script:plan -and $script:plan.head -ne $script:plan.target)
    $progress.Style = if ($Value) { 'Marquee' } else { 'Blocks' }
    $state.Text = $Message
}
function Add-Log([string]$Text) {
    $log.AppendText(('[' + (Get-Date -Format 'HH:mm:ss') + '] ' + $Text + "`r`n"))
}
function Begin-Action($Request, [string]$Mode, [string]$Message) {
    try {
        Set-Busy $true $Message
        Add-Log $Message
        if ($Request.action -in @('update','commit','push','start','stop')) {
            $Request.job = [Guid]::NewGuid().ToString('N')
            $script:job = $Request.job
        }
        Start-Rpc $Request $Mode
    } catch {
        Add-Log $_.Exception.Message
        Set-Busy $false '작업을 시작하지 못했습니다.'
    }
}
function Refresh-Status {
    Begin-Action (New-Request 'status') 'status' 'VPS 상태와 변경 파일을 확인하고 있습니다.'
}
function Confirm-Action([string]$Text) {
    return [System.Windows.Forms.MessageBox]::Show($form,$Text,'빡케몬 VPS 관리',
        [System.Windows.Forms.MessageBoxButtons]::YesNo,[System.Windows.Forms.MessageBoxIcon]::Question) -eq 'Yes'
}

$form = [System.Windows.Forms.Form]::new()
$form.Text = '빡케몬 VPS 관리'
$form.Size = [System.Drawing.Size]::new(1080,760)
$form.MinimumSize = [System.Drawing.Size]::new(1080,760)
$form.StartPosition = 'CenterScreen'
$form.Font = [System.Drawing.Font]::new('맑은 고딕',10)
$connection = [System.Windows.Forms.Label]::new()
$connection.Text = $settings.host + '  |  ' + $settings.root
$connection.SetBounds(18,15,1020,24)
$connection.Anchor = 'Top,Left,Right'
$form.Controls.Add($connection)
$state = [System.Windows.Forms.Label]::new()
$state.Text = '검사를 눌러 서버 상태와 변경 파일을 확인하세요.'
$state.SetBounds(18,43,1020,25)
$state.Anchor = 'Top,Left,Right'
$form.Controls.Add($state)
$progress = [System.Windows.Forms.ProgressBar]::new()
$progress.SetBounds(18,73,1020,7)
$progress.Anchor = 'Top,Left,Right'
$form.Controls.Add($progress)

function New-Button([string]$Text,[int]$X,[int]$Y,[int]$Width=142) {
    $b=[System.Windows.Forms.Button]::new(); $b.Text=$Text; $b.SetBounds($X,$Y,$Width,34)
    $form.Controls.Add($b); return $b
}
$refresh = New-Button '상태 / 변경 검사' 18 91 155
$checkUpdate = New-Button '업데이트 검사' 183 91
$apply = New-Button '기능 업데이트 적용' 335 91 167
$console = New-Button '서버 콘솔' 512 91 132
$start = New-Button '서버 시작' 654 91 115
$stop = New-Button '서버 종료' 779 91 115
$configuration = New-Button '연결 설정' 904 91 132
$apply.Enabled = $false

$hint = [System.Windows.Forms.Label]::new()
$hint.Text = '체크한 파일만 커밋합니다. JSON·properties 선택 가능 · 비밀 설정과 플레이어 데이터 제외'
$hint.SetBounds(18,137,1020,23)
$form.Controls.Add($hint)
$grid = [System.Windows.Forms.DataGridView]::new()
$grid.SetBounds(18,166,1020,300)
$grid.Anchor = 'Top,Bottom,Left,Right'
$grid.AllowUserToAddRows = $false
$grid.AllowUserToDeleteRows = $false
$grid.RowHeadersVisible = $false
$grid.SelectionMode = 'FullRowSelect'
$grid.AutoSizeColumnsMode = 'Fill'
$grid.BackgroundColor = [System.Drawing.Color]::White
$grid.MultiSelect = $true
$checkbox = [System.Windows.Forms.DataGridViewCheckBoxColumn]::new(); $checkbox.Name='Selected'; $checkbox.HeaderText='선택'; $checkbox.FillWeight=8
[void]$grid.Columns.Add($checkbox)
foreach ($col in @(@('Change','변경',10),@('Path','파일',72),@('Size','용량',10))) {
    $c=[System.Windows.Forms.DataGridViewTextBoxColumn]::new();$c.Name=$col[0];$c.HeaderText=$col[1];$c.FillWeight=$col[2];$c.ReadOnly=$true
    [void]$grid.Columns.Add($c)
}
$grid.Add_CurrentCellDirtyStateChanged({ if ($grid.IsCurrentCellDirty) { [void]$grid.CommitEdit('Commit') } })
$form.Controls.Add($grid)
$selectAll = New-Button '전체 선택' 18 476 110
$selectNone = New-Button '선택 해제' 138 476 110
$selectPlaza = New-Button '광장만 선택' 258 476 130
foreach($b in @($selectAll,$selectNone,$selectPlaza)) { $b.Anchor='Bottom,Left' }
$messageLabel=[System.Windows.Forms.Label]::new();$messageLabel.Text='커밋 메시지';$messageLabel.SetBounds(18,520,115,23);$messageLabel.Anchor='Bottom,Left';$form.Controls.Add($messageLabel)
$message=[System.Windows.Forms.TextBox]::new();$message.SetBounds(138,516,505,28);$message.Anchor='Bottom,Left,Right';$message.MaxLength=400;$form.Controls.Add($message)
$commit = New-Button '선택 파일 커밋·푸시' 654 514 183
$push = New-Button '기존 커밋 푸시' 847 514 189
foreach($b in @($commit,$push)) { $b.Anchor='Bottom,Right' }
$log=[System.Windows.Forms.TextBox]::new();$log.Multiline=$true;$log.ReadOnly=$true;$log.ScrollBars='Vertical';$log.SetBounds(18,557,1020,150);$log.Anchor='Bottom,Left,Right';$form.Controls.Add($log)
$actionControls=@($refresh,$checkUpdate,$console,$start,$stop,$configuration,$selectAll,$selectNone,$selectPlaza,$commit,$push,$grid,$message)

$refresh.Add_Click({ Refresh-Status })
$checkUpdate.Add_Click({ Begin-Action (New-Request 'plan') 'plan' 'GitHub 업데이트를 검사하고 있습니다.' })
$apply.Add_Click({
    if (-not $script:plan) { return }
    $text = '적용할 기능 파일: ' + @($script:plan.apply).Count + '개' + "`n기존 상태를 보존할 경로: " + @($script:plan.preserve).Count + '개' +
        "`n`n실행 중인 서버는 정상 저장·종료 후 업데이트하고 다시 시작합니다.`n진행하시겠습니까?"
    if (Confirm-Action $text) { $r=New-Request 'update';$r.head=$script:plan.head;$r.target=$script:plan.target;Begin-Action $r 'mutate' '기능 업데이트를 진행하고 있습니다.' }
})
$selectAll.Add_Click({ foreach($row in $grid.Rows) { $row.Cells['Selected'].Value=$true } })
$selectNone.Add_Click({ foreach($row in $grid.Rows) { $row.Cells['Selected'].Value=$false } })
$selectPlaza.Add_Click({ foreach($row in $grid.Rows) { $row.Cells['Selected'].Value=([string]$row.Cells['Path'].Value).StartsWith('world/dimensions/jbro_policy/plaza/') } })
$commit.Add_Click({
    [void]$grid.EndEdit()
    $names=@($grid.Rows | Where-Object { $_.Cells['Selected'].Value -eq $true } | ForEach-Object { [string]$_.Cells['Path'].Value })
    if ($names.Count -eq 0 -or [string]::IsNullOrWhiteSpace($message.Text)) {
        [void][System.Windows.Forms.MessageBox]::Show($form,'파일을 체크하고 커밋 메시지를 입력해주세요.');return
    }
    $text=($names -join "`n") + "`n`n위 파일만 커밋·푸시합니다. 실행 중이면 정상 저장·종료 후 다시 시작합니다."
    if (Confirm-Action $text) {
        $r=New-Request 'commit';$r.files=$names;$r.message=$message.Text;$r.head=$script:head
        Begin-Action $r 'mutate' '선택 파일 커밋·푸시를 진행하고 있습니다.'
    }
})
$push.Add_Click({ if (Confirm-Action '이미 만들어진 main 커밋을 푸시합니다. 현재 변경 파일을 추가로 커밋하지 않습니다.') { Begin-Action (New-Request 'push') 'mutate' '기존 커밋을 푸시하고 있습니다.' } })
$start.Add_Click({ Begin-Action (New-Request 'start') 'mutate' '서버 시작을 확인하고 있습니다.' })
$stop.Add_Click({ if (Confirm-Action '월드를 저장한 뒤 서버를 정상 종료하시겠습니까?') { Begin-Action (New-Request 'stop') 'mutate' '월드를 저장하고 서버를 정상 종료하고 있습니다.' } })
$configuration.Add_Click({ Start-Process -FilePath 'notepad.exe' -ArgumentList ('"' + $SettingsPath + '"') })
$console.Add_Click({
    if (Test-Path -LiteralPath $settings.consoleShortcut) { Start-Process -FilePath $settings.consoleShortcut;return }
    [void][System.Windows.Forms.MessageBox]::Show($form,'기존 빡케몬 서버 콘솔 바로가기를 찾을 수 없습니다. 연결 설정의 consoleShortcut을 확인해주세요.')
})

$timer=[System.Windows.Forms.Timer]::new();$timer.Interval=250
$timer.Add_Tick({
    try {
        if ($script:process) {
            if (-not $script:inputClosed -and $script:inputTask.IsCompleted) {
                $script:process.StandardInput.Close();$script:inputClosed=$true
            }
            if (-not $script:process.HasExited -or -not $script:stdoutTask.IsCompleted -or -not $script:stderrTask.IsCompleted) { return }
            $output=$script:stdoutTask.GetAwaiter().GetResult();$errors=$script:stderrTask.GetAwaiter().GetResult();$exit=$script:process.ExitCode
            $mode=$script:mode;$script:process.Dispose();$script:process=$null
            if ($exit -ne 0) { throw ('SSH 연결 오류: ' + $errors.Trim()) }
            $response=$output | ConvertFrom-Json
            if (-not $response.ok) { throw $response.error }
            $data=$response.data;$script:failures=0;$script:lastError=$null
            switch ($mode) {
                'status' {
                    $script:head=$data.head;$script:plan=$null
                    $script:running=[bool]$data.running
                    $grid.Rows.Clear()
                    foreach($entry in $data.changes) {
                        $change=if($entry.status -match 'D'){'삭제'}elseif($entry.status -eq '??' -or $entry.status -match 'A'){'추가'}else{'수정'}
                        $size=if($entry.size -ge 1048576){'{0:N1} MB' -f ($entry.size/1048576)}else{'{0:N1} KB' -f ($entry.size/1024)}
                        [void]$grid.Rows.Add($false,$change,$entry.path,$size)
                    }
                    $running=if($data.running){'실행 중'}else{'종료됨'}
                    Set-Busy $false ('서버 ' + $running + ' | 변경 ' + @($data.changes).Count + '개 | 미푸시 ' + $data.ahead + ' / 미반영 ' + $data.behind + ' | 제외 ' + $data.excluded + '개')
                    Add-Log ('검사 완료. main ' + $data.head.Substring(0,8))
                }
                'plan' {
                    $script:plan=$data
                    Add-Log ('업데이트 대상: ' + $data.head.Substring(0,8) + ' → ' + $data.target.Substring(0,8))
                    foreach($path in $data.apply){Add-Log ('적용: ' + $path)}
                    foreach($path in $data.preserve){Add-Log ('보존: ' + $path)}
                    Set-Busy $false $(if($data.head -eq $data.target){'이미 최신 커밋입니다.'}else{'업데이트 검사 완료. 적용 목록을 확인한 뒤 기능 업데이트 적용을 누르세요.'})
                }
                'mutate' { $script:nextPoll=[DateTime]::Now; $state.Text='원격 작업이 진행 중입니다.' }
                'poll' {
                    if($data.finished) {
                        $script:job=$null
                        if(-not $data.result.ok){throw $data.result.error}
                        Add-Log $data.result.data.message
                        if($data.result.data.commit){Add-Log ('커밋: ' + $data.result.data.commit)}
                        if($data.result.data.backup){Add-Log ('백업: ' + $data.result.data.backup)}
                        $script:plan=$null
                        Refresh-Status
                    } else { $state.Text=$data.message;$script:nextPoll=[DateTime]::Now.AddSeconds(3) }
                }
            }
        } elseif ($script:busy -and $script:job -and [DateTime]::Now -ge $script:nextPoll) {
            $r=New-Request 'job';$r.job=$script:job;Start-Rpc $r 'poll'
        }
    } catch {
        $script:lastError=$_.Exception.Message
        Add-Log $_.Exception.Message
        if($script:process){$script:process.Dispose();$script:process=$null}
        if($script:job -and $script:failures -lt 3) {
            $script:failures++;$script:nextPoll=[DateTime]::Now.AddSeconds(5)
            $state.Text='연결을 확인하고 원격 작업 결과를 다시 조회하고 있습니다.'
        } else {
            $script:job=$null;Set-Busy $false '작업 결과를 확인하지 못했습니다. 상태 / 변경 검사를 눌러 확인해주세요.'
        }
    }
})
$form.Add_FormClosing({ param($sender,$eventArgs) if($script:busy){$eventArgs.Cancel=$true;[void][System.Windows.Forms.MessageBox]::Show($form,'진행 중인 작업이 끝난 뒤 닫아주세요.')} })
if($RenderUiPath) {
    $state.Text='서버 실행 중 | 변경 3개 | 미푸시 0 / 미반영 0'
    [void]$grid.Rows.Add($true,'수정','config/example.json','0.2 KB')
    [void]$grid.Rows.Add($false,'수정','server.properties','1.5 KB')
    [void]$grid.Rows.Add($false,'수정','world/dimensions/jbro_policy/plaza/region/r.0.0.mca','3.8 MB')
    $form.Show();[System.Windows.Forms.Application]::DoEvents()
    $bitmap=[System.Drawing.Bitmap]::new($form.Width,$form.Height)
    $form.DrawToBitmap($bitmap,[System.Drawing.Rectangle]::new(0,0,$form.Width,$form.Height))
    $bitmap.Save($RenderUiPath);$bitmap.Dispose();$form.Close();$form.Dispose();exit
}
if($Probe) {
    $timer.Start();Refresh-Status
    while($script:busy -or $script:process) { [System.Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 100 }
    if($ProbeAction -eq 'start' -and -not $script:running){throw '실행 중인 서버에 대한 중복 시작 검사만 허용합니다.'}
    if($ProbeAction -ne 'status') {
        Begin-Action (New-Request $ProbeAction) $(if($ProbeAction -eq 'plan'){'plan'}else{'mutate'}) '연결 경로를 검증하고 있습니다.'
        while($script:busy -or $script:process) { [System.Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 100 }
    }
    $timer.Stop()
    if($script:lastError){throw $script:lastError}
    if(-not $script:head){throw 'VPS 상태 검사를 완료하지 못했습니다.'}
    [PSCustomObject]@{head=$script:head;changes=$grid.Rows.Count;status=$state.Text} | ConvertTo-Json -Compress
    $form.Dispose();exit
}
$form.Add_Shown({$timer.Start();Refresh-Status})
[void]$form.ShowDialog()
$timer.Stop();$timer.Dispose();$form.Dispose()
