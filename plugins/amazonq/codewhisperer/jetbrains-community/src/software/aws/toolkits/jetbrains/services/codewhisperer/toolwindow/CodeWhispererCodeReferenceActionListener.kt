// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.toolwindow

import com.intellij.openapi.editor.RangeMarker
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContextNew
import software.aws.toolkits.jetbrains.services.codewhisperer.model.PreviewContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.SessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.SessionContextNew
import software.aws.toolkits.jetbrains.services.codewhisperer.popup.CodeWhispererUserActionListener

class CodeWhispererCodeReferenceActionListener : CodeWhispererUserActionListener {
    override fun afterAccept(states: InvocationContext, sessionContext: SessionContext, rangeMarker: RangeMarker) {
        val (project, editor) = states.requestContext
        val manager = CodeWhispererCodeReferenceManager.getInstance(project)
        manager.insertCodeReference(states, sessionContext.selectedIndex)
        manager.addListeners(editor)
    }
}

class CodeWhispererCodeReferenceActionListenerNew : CodeWhispererUserActionListener {
    override fun afterAccept(states: InvocationContextNew, previews: List<PreviewContext>, sessionContext: SessionContextNew, rangeMarker: RangeMarker) {
        val manager = CodeWhispererCodeReferenceManager.getInstance(sessionContext.project)
        manager.insertCodeReference(states, previews, sessionContext.selectedIndex)
        manager.addListeners(sessionContext.editor)
    }
}
