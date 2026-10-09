param(
    [string]$RepositoryRoot=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))),
    [ValidateSet('Server','Client')][string]$Tab='Client',
    [switch]$LibraryOnly
)
$ErrorActionPreference='Stop'

function New-ProductPatchWindow([string]$Repo,[string]$InitialTab='Client',[switch]$QuietErrors) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $serverScript=Join-Path $PSScriptRoot 'server/deploy-server.ps1'
    $clientScript=Join-Path $PSScriptRoot 'client/patch-client.ps1'
    if(-not(Test-Path -LiteralPath $serverScript)){$serverScript=Join-Path $Repo 'works/tools/server-deploy/deploy-server.ps1'}
    if(-not(Test-Path -LiteralPath $clientScript)){$clientScript=Join-Path $Repo 'works/tools/client-patch/patch-client.ps1'}
    # Construct each page immediately after loading its library. Each worker captures its own module.
    . $serverScript -LibraryOnly
    $server=New-DeploymentWindow (Join-Path $Repo 'develop-product/server') (Join-Path ([IO.Path]::GetDirectoryName($Repo)) 'MinecraftPPakemonServer') '' -QuietErrors:$QuietErrors
    . $clientScript -WikiRoot (Join-Path $Repo 'server-wiki') -LibraryOnly
    $client=New-ClientPatchWindow (Join-Path $Repo 'develop-product/client') (Join-Path $env:APPDATA 'ModrinthApp/profiles/PPakemon') '' -QuietErrors:$QuietErrors
    $form=[Windows.Forms.Form]::new()
    $form.Text='빡케몬 서버 / 클라이언트 패쳐'
    $form.ClientSize=[Drawing.Size]::new(1040,790)
    $form.MinimumSize=[Drawing.Size]::new(1040,790)
    $form.StartPosition='CenterScreen'
    $form.Font=[Drawing.Font]::new('맑은 고딕',10)
    $tabs=[Windows.Forms.TabControl]::new();$tabs.Dock='Fill';$tabs.Padding=[Drawing.Point]::new(24,8)
    $form.Controls.Add($tabs)
    foreach($pair in @(@('서버',$server),@('클라이언트',$client))){
        $page=[Windows.Forms.TabPage]::new($pair[0]);$tabs.TabPages.Add($page)
        $child=$pair[1].Form;$child.TopLevel=$false;$child.FormBorderStyle='None';$child.Dock='Fill';$child.MinimumSize=[Drawing.Size]::Empty
        $page.Controls.Add($child);$child.Show()
    }
    $tabs.SelectedIndex=if($InitialTab -eq 'Server'){0}else{1}
    $form.Add_FormClosing({param($sender,$event)
        if($server.Context.Busy -or $client.Context.Busy){$event.Cancel=$true}
    }.GetNewClosure())
    $form.Add_FormClosed({$server.Form.Dispose();$client.Form.Dispose()}.GetNewClosure())
    return [pscustomobject]@{Form=$form;Tabs=$tabs;Server=$server;Client=$client}
}

if($LibraryOnly){return}
try{
    $window=New-ProductPatchWindow $RepositoryRoot $Tab
    try{$window.Form.ShowDialog()|Out-Null}finally{$window.Form.Dispose()}
}catch{
    Add-Type -AssemblyName System.Windows.Forms
    [Windows.Forms.MessageBox]::Show($_.Exception.Message,'통합 패쳐 실행 실패','OK','Error')|Out-Null
    exit 1
}
